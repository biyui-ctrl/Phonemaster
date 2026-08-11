const {admin, db} = require("../lib/firebase");
const {callable, HttpsError} = require("../lib/callable");
const {PAIR_JOIN_TTL_MS, MAX_START_PAIRS_PER_HOUR, hash, randomDigits} = require("../lib/pairs");

const {Timestamp} = admin.firestore;

module.exports = callable(async ({uid}) => {
  const now = Date.now();
  const store = db();
  const pairRef = store.collection("pairs").doc();
  const counterRef = store.collection("pairRateLimits").doc(uid);
  const joinCode = randomDigits(8);

  await store.runTransaction(async (tx) => {
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
