const crypto = require("crypto");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();
const {Timestamp} = admin.firestore;

const PAIR_JOIN_TTL_MS = 10 * 60 * 1000;
const MESSAGE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_JOIN_ATTEMPTS = 8;
const MAX_RELAYS_PER_MINUTE = 60;
const MAX_START_PAIRS_PER_HOUR = 12;

function protectedCall(handler) {
  return onCall({enforceAppCheck: true}, handler);
}

function requireUid(request) {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  return uid;
}

function requirePairId(value) {
  const pairId = String(value || "");
  if (!/^[A-Za-z0-9_-]{10,128}$/.test(pairId)) {
    throw new HttpsError("invalid-argument", "Invalid pair ID.");
  }
  return pairId;
}

function hash(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function randomDigits(length) {
  let value = "";
  while (value.length < length) value += crypto.randomInt(0, 10).toString();
  return value;
}

exports.startPair = protectedCall(async (request) => {
  const uid = requireUid(request);
  const now = Date.now();
  const pairRef = db.collection("pairs").doc();
  const counterRef = db.collection("pairRateLimits").doc(uid);
  const joinCode = randomDigits(8);

  await db.runTransaction(async (tx) => {
    const counterSnap = await tx.get(counterRef);
    const counter = counterSnap.exists ? counterSnap.data() : {};
    const start = Number(counter.windowStartMs || 0);
    const inWindow = now - start < 60 * 60 * 1000;
    const count = inWindow ? Number(counter.count || 0) : 0;
    if (inWindow && count >= MAX_START_PAIRS_PER_HOUR) {
      throw new HttpsError("resource-exhausted", "Too many pairing attempts. Try again later.");
    }

    tx.set(counterRef, {
      windowStartMs: inWindow ? start : now,
      count: count + 1,
      expiresAt: Timestamp.fromMillis(now + 2 * 60 * 60 * 1000),
    }, {merge: true});

    tx.set(pairRef, {
      ownerUid: uid,
      receiverUid: null,
      receiverToken: null,
      joinCodeHash: hash(joinCode),
      joinExpiresAt: Timestamp.fromMillis(now + PAIR_JOIN_TTL_MS),
      joinAttempts: 0,
      revoked: false,
      nextSeq: 0,
      rateWindowStartMs: now,
      rateCount: 0,
      createdAt: Timestamp.now(),
    });
  });

  return {pairId: pairRef.id, joinCode, expiresInSeconds: 600};
});

exports.joinPair = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const joinCode = String(request.data?.joinCode || "");
  if (!/^\d{8}$/.test(joinCode)) throw new HttpsError("invalid-argument", "Invalid pairing code.");

  const ref = db.collection("pairs").doc(pairId);
  const outcome = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return "not-found";
    const pair = snap.data();
    if (pair.revoked) return "revoked";
    if (pair.ownerUid === uid) return "same-install";
    if (pair.receiverUid && pair.receiverUid !== uid) return "already-paired";
    if (!pair.joinExpiresAt || Date.now() > pair.joinExpiresAt.toMillis()) return "expired";

    const attempts = Number(pair.joinAttempts || 0);
    if (attempts >= MAX_JOIN_ATTEMPTS) return "locked";
    if (pair.joinCodeHash !== hash(joinCode)) {
      tx.update(ref, {joinAttempts: attempts + 1});
      return "wrong-code";
    }

    tx.update(ref, {
      receiverUid: uid,
      joinedAt: Timestamp.now(),
      joinCodeHash: admin.firestore.FieldValue.delete(),
      joinExpiresAt: admin.firestore.FieldValue.delete(),
      joinAttempts: admin.firestore.FieldValue.delete(),
    });
    return "ok";
  });

  if (outcome === "ok") return {ok: true};
  const errors = {
    "not-found": ["not-found", "Pair not found."],
    "revoked": ["permission-denied", "Pair revoked."],
    "same-install": ["failed-precondition", "Phone B must be a different app installation."],
    "already-paired": ["already-exists", "A receiver is already paired."],
    "expired": ["deadline-exceeded", "Pairing code expired."],
    "locked": ["resource-exhausted", "Pairing locked after too many failures."],
    "wrong-code": ["permission-denied", "Incorrect pairing code."],
  };
  const [code, message] = errors[outcome] || ["internal", "Pairing failed."];
  throw new HttpsError(code, message);
});

exports.registerReceiverToken = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const token = String(request.data?.token || "");
  if (token.length < 20 || token.length > 4096) throw new HttpsError("invalid-argument", "Invalid token.");

  const ref = db.collection("pairs").doc(pairId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "Pair not found.");
  const pair = snap.data();
  if (pair.revoked || pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not the active receiver.");

  await ref.update({receiverToken: token, receiverTokenUpdatedAt: Timestamp.now()});
  return {ok: true};
});

exports.relaySms = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const relayId = String(request.data?.relayId || "");
  const iv = String(request.data?.iv || "");
  const ciphertext = String(request.data?.ciphertext || "");
  const smsTimestamp = Number(request.data?.timestamp || Date.now());

  if (!/^[0-9a-fA-F-]{36}$/.test(relayId)) throw new HttpsError("invalid-argument", "Invalid relay ID.");
  if (!iv || !ciphertext || iv.length > 128 || ciphertext.length > 32768) {
    throw new HttpsError("invalid-argument", "Invalid encrypted payload.");
  }
  if (!Number.isFinite(smsTimestamp) || smsTimestamp < 0) throw new HttpsError("invalid-argument", "Invalid timestamp.");

  const pairRef = db.collection("pairs").doc(pairId);
  const messageRef = pairRef.collection("messages").doc(relayId);
  const now = Date.now();

  const result = await db.runTransaction(async (tx) => {
    const pairSnap = await tx.get(pairRef);
    if (!pairSnap.exists) throw new HttpsError("not-found", "Pair not found.");
    const pair = pairSnap.data();
    if (pair.revoked || pair.ownerUid !== uid) throw new HttpsError("permission-denied", "Not the active source phone.");
    if (!pair.receiverUid) throw new HttpsError("failed-precondition", "Phone B is not paired.");

    const existing = await tx.get(messageRef);
    if (existing.exists) return {duplicate: true, seq: Number(existing.get("seq") || 0), token: pair.receiverToken || null};

    const start = Number(pair.rateWindowStartMs || 0);
    const inWindow = now - start < 60 * 1000;
    const count = inWindow ? Number(pair.rateCount || 0) : 0;
    if (inWindow && count >= MAX_RELAYS_PER_MINUTE) throw new HttpsError("resource-exhausted", "Relay rate exceeded.");

    const seq = Number(pair.nextSeq || 0) + 1;
    tx.update(pairRef, {
      nextSeq: seq,
      rateWindowStartMs: inWindow ? start : now,
      rateCount: count + 1,
    });
    tx.set(messageRef, {
      iv,
      ciphertext,
      smsTimestamp,
      seq,
      createdAt: Timestamp.now(),
      expiresAt: Timestamp.fromMillis(now + MESSAGE_RETENTION_MS),
    });
    return {duplicate: false, seq, token: pair.receiverToken || null};
  });

  if (!result.duplicate && result.token) {
    try {
      await admin.messaging().send({
        token: result.token,
        data: {type: "relayed_sms", pairId, messageId: relayId, seq: String(result.seq)},
        android: {priority: "high"},
      });
    } catch (error) {
      console.error("FCM delivery failed; reconciliation can recover the message.", error);
    }
  }
  return {ok: true, duplicate: result.duplicate, seq: result.seq};
});

exports.getMessages = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const afterSeq = Math.max(0, Number(request.data?.afterSeq || 0));
  if (!Number.isFinite(afterSeq)) throw new HttpsError("invalid-argument", "Invalid cursor.");

  const pairRef = db.collection("pairs").doc(pairId);
  const pairSnap = await pairRef.get();
  if (!pairSnap.exists) throw new HttpsError("not-found", "Pair not found.");
  const pair = pairSnap.data();
  if (pair.revoked || pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not the active receiver.");

  const query = await pairRef.collection("messages")
    .where("seq", ">", afterSeq)
    .orderBy("seq", "asc")
    .limit(100)
    .get();

  const now = Date.now();
  const messages = query.docs
    .filter((doc) => !doc.get("expiresAt") || doc.get("expiresAt").toMillis() > now)
    .map((doc) => ({
      messageId: doc.id,
      iv: doc.get("iv"),
      ciphertext: doc.get("ciphertext"),
      timestamp: doc.get("smsTimestamp"),
      seq: doc.get("seq"),
    }));
  const cursorSeq = query.empty ? afterSeq : Number(query.docs[query.docs.length - 1].get("seq"));
  return {messages, cursorSeq};
});

exports.pairStatus = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const snap = await db.collection("pairs").doc(pairId).get();
  if (!snap.exists) throw new HttpsError("not-found", "Pair not found.");
  const pair = snap.data();
  if (pair.ownerUid !== uid && pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not a member of this pair.");
  return {joined: Boolean(pair.receiverUid), revoked: Boolean(pair.revoked)};
});

exports.revokePair = protectedCall(async (request) => {
  const uid = requireUid(request);
  const pairId = requirePairId(request.data?.pairId);
  const ref = db.collection("pairs").doc(pairId);
  let token = null;

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const pair = snap.data();
    if (pair.ownerUid !== uid && pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not a member of this pair.");
    token = pair.receiverToken || null;
    tx.update(ref, {
      revoked: true,
      revokedAt: Timestamp.now(),
      receiverToken: null,
      joinCodeHash: admin.firestore.FieldValue.delete(),
      joinExpiresAt: admin.firestore.FieldValue.delete(),
    });
  });

  if (token) {
    try {
      await admin.messaging().send({token, data: {type: "pair_revoked", pairId}, android: {priority: "high"}});
    } catch (error) {
      console.error("Revocation push failed.", error);
    }
  }
  return {ok: true};
});
