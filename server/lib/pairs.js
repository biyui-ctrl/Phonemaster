const crypto = require("crypto");
const {HttpsError} = require("./callable");

const PAIR_JOIN_TTL_MS = 10 * 60 * 1000;
const MESSAGE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_JOIN_ATTEMPTS = 8;
const MAX_RELAYS_PER_MINUTE = 60;
const MAX_START_PAIRS_PER_HOUR = 12;

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

module.exports = {
  PAIR_JOIN_TTL_MS,
  MESSAGE_RETENTION_MS,
  MAX_JOIN_ATTEMPTS,
  MAX_RELAYS_PER_MINUTE,
  MAX_START_PAIRS_PER_HOUR,
  requirePairId,
  hash,
  randomDigits,
};
