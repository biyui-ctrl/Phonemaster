const {db} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {requirePairId} = require("../lib/pairs");

module.exports = callable(async ({uid, data}) => {
  const pairId = requirePairId(data?.pairId);
  const afterSeq = Math.max(0, Number(data?.afterSeq || 0));
  if (!Number.isFinite(afterSeq)) throw new HttpsError("invalid-argument", "Invalid cursor.");

  const pairRef = db().collection("pairs").doc(pairId);
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
