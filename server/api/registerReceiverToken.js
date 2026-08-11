const {admin, db} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {requirePairId} = require("../lib/pairs");

const {Timestamp} = admin.firestore;

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const token = String(data?.token || "");
  if (token.length < 20 || token.length > 4096) throw new HttpsError("invalid-argument", "Invalid token.");

  const ref = db().collection("pairs").doc(pairId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "Pair not found.");
  const pair = snap.data();
  if (pair.revoked || pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not the active receiver.");

  await ref.update({receiverToken: token, receiverTokenUpdatedAt: Timestamp.now()});
  return {ok: true};
});
