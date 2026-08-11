const {admin, db, messaging} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {requirePairId} = require("../lib/pairs");

const {Timestamp} = admin.firestore;

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const ref = db().collection("pairs").doc(pairId);
  let token = null;

  await db().runTransaction(async (tx) => {
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
      await messaging().send({token, data: {type: "pair_revoked", pairId}, android: {priority: "high"}});
    } catch (error) {
      console.error("Revocation push failed.", error);
    }
  }
  return {ok: true};
});
