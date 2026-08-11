const {auth, appCheck} = require("./firebase");

// Wire-compatible with the Firebase "callable functions" protocol so the Android
// client can keep using FirebaseFunctions (via getHttpsCallableFromUrl) instead
// of hand-rolling HTTP. That protocol is:
//   request  -> POST {"data": <payload>}
//               Authorization: Bearer <Firebase ID token>
//               X-Firebase-AppCheck: <App Check token>
//   success  -> 200 {"result": <payload>}
//   failure  -> <status> {"error": {"status": <CODE>, "message": <text>}}
const HTTP_STATUS = {
  "cancelled": 499,
  "unknown": 500,
  "invalid-argument": 400,
  "deadline-exceeded": 504,
  "not-found": 404,
  "already-exists": 409,
  "permission-denied": 403,
  "resource-exhausted": 429,
  "failed-precondition": 400,
  "aborted": 409,
  "out-of-range": 400,
  "unimplemented": 501,
  "internal": 500,
  "unavailable": 503,
  "data-loss": 500,
  "unauthenticated": 401,
};

const CANONICAL_CODE = {
  "cancelled": "CANCELLED",
  "unknown": "UNKNOWN",
  "invalid-argument": "INVALID_ARGUMENT",
  "deadline-exceeded": "DEADLINE_EXCEEDED",
  "not-found": "NOT_FOUND",
  "already-exists": "ALREADY_EXISTS",
  "permission-denied": "PERMISSION_DENIED",
  "resource-exhausted": "RESOURCE_EXHAUSTED",
  "failed-precondition": "FAILED_PRECONDITION",
  "aborted": "ABORTED",
  "out-of-range": "OUT_OF_RANGE",
  "unimplemented": "UNIMPLEMENTED",
  "internal": "INTERNAL",
  "unavailable": "UNAVAILABLE",
  "data-loss": "DATA_LOSS",
  "unauthenticated": "UNAUTHENTICATED",
};

class HttpsError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "HttpsError";
    this.code = HTTP_STATUS[code] ? code : "internal";
  }
}

function sendError(res, error) {
  const code = error instanceof HttpsError ? error.code : "internal";
  const message = error instanceof HttpsError ? error.message : "Internal error.";
  if (!(error instanceof HttpsError)) {
    // Never leak internal failure detail to the client; keep it in the logs.
    console.error("Unhandled handler failure:", error);
  }
  res.status(HTTP_STATUS[code]).json({
    error: {status: CANONICAL_CODE[code], message},
  });
}

function bearerToken(req) {
  const header = req.headers.authorization || "";
  const match = /^Bearer (.+)$/.exec(header);
  return match ? match[1] : null;
}

// App Check proves the caller is a genuine, unmodified build of the app rather
// than a script hitting the endpoint directly.
//
// "enforce"  - reject any request without a valid App Check token (default).
// "soft"     - verify when a token is present, allow the call when it is absent.
// "off"      - skip App Check entirely, including tokens that fail to verify.
//              Required when the app is not distributed through Google Play:
//              Play Integrity still emits a token, but it cannot be validated,
//              so "soft" is not enough. The endpoints are then protected by
//              Firebase Auth plus the per-pair authorisation checks alone.
function appCheckMode() {
  const mode = process.env.APP_CHECK_MODE;
  return mode === "soft" || mode === "off" ? mode : "enforce";
}

async function verifyAppCheck(req) {
  const mode = appCheckMode();
  if (mode === "off") return;

  const token = req.headers["x-firebase-appcheck"];

  if (!token) {
    if (mode === "soft") return;
    throw new HttpsError("unauthenticated", "App Check token missing.");
  }

  try {
    await appCheck().verifyToken(String(token));
  } catch (error) {
    // An invalid token is always rejected, even in soft mode — soft mode only
    // tolerates a missing token, never a forged one.
    throw new HttpsError("unauthenticated", "App Check verification failed.");
  }
}

async function verifyUid(req) {
  const token = bearerToken(req);
  if (!token) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const decoded = await auth().verifyIdToken(token);
    return decoded.uid;
  } catch (error) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
}

// The callable protocol does not put 64-bit integers on the wire as plain JSON
// numbers, because JSON cannot represent the full int64 range safely. It wraps
// them:
//   {"@type": "type.googleapis.com/google.protobuf.Int64Value", "value": "123"}
// Firebase's own Cloud Functions SDK unwraps these before the handler runs, so
// handlers ported from it must do the same or every Long arrives as an object.
const INT64_TYPES = new Set([
  "type.googleapis.com/google.protobuf.Int64Value",
  "type.googleapis.com/google.protobuf.UInt64Value",
]);

function decodeWireValue(value) {
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(decodeWireValue);

  if (INT64_TYPES.has(value["@type"])) {
    const parsed = Number(value.value);
    if (!Number.isFinite(parsed)) {
      throw new HttpsError("invalid-argument", "Invalid numeric value.");
    }
    return parsed;
  }

  const decoded = {};
  for (const [key, item] of Object.entries(value)) {
    decoded[key] = decodeWireValue(item);
  }
  return decoded;
}

function parseData(req) {
  const body = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
  if (body && typeof body === "object" && "data" in body) {
    return decodeWireValue(body.data ?? {});
  }
  return {};
}

// handler receives ({uid, data}) and returns the value to put in "result".
function callable(handler) {
  return async (req, res) => {
    try {
      if (req.method !== "POST") {
        throw new HttpsError("unimplemented", "Only POST is supported.");
      }
      await verifyAppCheck(req);
      const uid = await verifyUid(req);

      let data;
      try {
        data = parseData(req);
      } catch (error) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("invalid-argument", "Request body must be JSON.");
      }

      const result = await handler({uid, data});
      res.status(200).json({result: result ?? null});
    } catch (error) {
      sendError(res, error);
    }
  };
}

module.exports = {callable, HttpsError};
