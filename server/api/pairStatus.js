const {db} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {requirePairId} = require("../lib/pairs");

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const snap = await db().collection("pairs").doc(pairId).get();
  if (!snap.exists) throw new HttpsError("not-found", "Pair not found.");
  const pair = snap.data();
  if (pair.ownerUid !== uid && pair.receiverUid !== uid) throw new HttpsError("permission-denied", "Not a member of this pair.");
  return {joined: Boolean(pair.receiverUid), revoked: Boolean(pair.revoked)};
});
