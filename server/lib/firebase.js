const admin = require("firebase-admin");

// The service-account JSON is supplied as an environment variable rather than a
// file on disk. Base64 is preferred because it survives copy/paste into the
// Vercel dashboard without newline mangling.
function loadServiceAccount() {
  const encoded = process.env.FIREBASE_SERVICE_ACCOUNT_JSON_BASE64;
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  const json = encoded ? Buffer.from(encoded, "base64").toString("utf8") : raw;
  if (!json) {
    throw new Error(
      "Missing FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 (or FIREBASE_SERVICE_ACCOUNT_JSON)."
    );
  }
  try {
    return JSON.parse(json);
  } catch (error) {
    throw new Error("Service account credentials are not valid JSON.");
  }
}

// Vercel reuses a warm instance across invocations, so initialise once per
// process and reuse the handles on subsequent requests.
function app() {
  if (admin.apps.length) return admin.app();
  return admin.initializeApp({
    credential: admin.credential.cert(loadServiceAccount()),
  });
}

const db = () => admin.firestore(app());
const auth = () => admin.auth(app());
const appCheck = () => admin.appCheck(app());
const messaging = () => admin.messaging(app());

module.exports = {admin, app, db, auth, appCheck, messaging};
