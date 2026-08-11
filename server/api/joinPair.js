const {admin, db} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {MAX_JOIN_ATTEMPTS, requirePairId, hash} = require("../lib/pairs");

const {Timestamp} = admin.firestore;

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const joinCode = String(data?.joinCode || "");
  if (!/^\d{8}$/.test(joinCode)) throw new HttpsError("invalid-argument", "Invalid pairing code.");

  const store = db();
  const ref = store.collection("pairs").doc(pairId);
  const outcome = await store.runTransaction(async (tx) => {
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
