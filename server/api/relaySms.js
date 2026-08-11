const {admin, db, messaging} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {MESSAGE_RETENTION_MS, MAX_RELAYS_PER_MINUTE, requirePairId} = require("../lib/pairs");

const {Timestamp} = admin.firestore;

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const relayId = String(data?.relayId || "");
  const iv = String(data?.iv || "");
  const ciphertext = String(data?.ciphertext || "");
  const smsTimestamp = Number(data?.timestamp || Date.now());

  if (!/^[0-9a-fA-F-]{36}$/.test(relayId)) throw new HttpsError("invalid-argument", "Invalid relay ID.");
  if (!iv || !ciphertext || iv.length > 128 || ciphertext.length > 32768) {
    throw new HttpsError("invalid-argument", "Invalid encrypted payload.");
  }
  if (!Number.isFinite(smsTimestamp) || smsTimestamp < 0) throw new HttpsError("invalid-argument", "Invalid timestamp.");

  const store = db();
  const pairRef = store.collection("pairs").doc(pairId);
  const messageRef = pairRef.collection("messages").doc(relayId);
  const now = Date.now();

  const result = await store.runTransaction(async (tx) => {
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
      await messaging().send({
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
