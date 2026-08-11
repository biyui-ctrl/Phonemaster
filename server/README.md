# Phonemaster API

The seven endpoints the Android app calls. These used to be Firebase Cloud
Functions; they now run as ordinary serverless functions so the Firebase project
can stay on the free Spark plan (Cloud Functions require the Blaze plan).

Firestore, Firebase Authentication, Cloud Messaging and App Check are unchanged
and still provided by Firebase — only the API moved.

## Endpoints

Each file in `api/` is one endpoint, reachable at `/api/<name>`:

`startPair`, `joinPair`, `registerReceiverToken`, `relaySms`, `getMessages`,
`pairStatus`, `revokePair`

They implement the Firebase **callable protocol**, so the Android client keeps
using `FirebaseFunctions` (through `getHttpsCallableFromUrl`) and continues to
attach the Firebase Auth ID token and App Check token automatically:

```
POST /api/startPair
Authorization: Bearer <Firebase ID token>
X-Firebase-AppCheck: <App Check token>
{"data": {...}}

200 {"result": {...}}
4xx {"error": {"status": "PERMISSION_DENIED", "message": "..."}}
```

Every request is rejected unless the Firebase ID token verifies. Message bodies
are encrypted on Phone A before they ever reach this service, so the server only
ever stores and forwards ciphertext.

## Deploying to Vercel

1. Sign in at vercel.com with GitHub (free Hobby plan, no card required).
2. **Add New → Project**, import the `Phonemaster` repository.
3. Set **Root Directory** to `server`. This matters — the repo root is an
   Android project.
4. Add an environment variable (Settings → Environment Variables):

   | Name | Value |
   |---|---|
   | `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64` | base64 of the service-account JSON |

   The same value as the `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64` GitHub secret.
   Apply it to Production, Preview and Development.

5. Deploy. Vercel redeploys automatically on every push to `main`.

Your API base URL is then `https://<project>.vercel.app/api`.

## Pointing the app at it

Set the repository **variable** `PHONEMASTER_API_BASE_URL` (Settings → Secrets
and variables → Actions → Variables) to that base URL, including `/api` and
without a trailing slash. Both the CI and release APK workflows read it and bake
it into `BuildConfig.API_BASE_URL`.

For a local build: `./gradlew :app:assembleRelease -PphonemasterApiBaseUrl=https://<project>.vercel.app/api`

If the URL is missing at build time the app fails fast with a clear message
rather than producing a confusing runtime error.

## App Check enforcement

Currently set to `off` in `vercel.json`. The app is sideloaded, and Play
Integrity cannot attest a build Google Play has never seen: it still emits an
App Check token, but that token fails verification, so `soft` is not sufficient —
only `off` gets past it. Set this back to `enforce` once the app is registered
with Google Play (an internal testing track is enough).

`APP_CHECK_MODE` controls how strictly App Check is applied:

| Value | Behaviour |
|---|---|
| `enforce` (default) | Reject any request without a valid App Check token. |
| `soft` | Accept a request with no App Check token; still reject an invalid one. |
| `off` | Skip App Check entirely, including tokens that fail verification. |

`enforce` is the stronger setting and matches the original Cloud Functions
behaviour. It requires App Check to be able to issue tokens for the installed
app — with the Play Integrity provider, that generally means the app must be
registered with Google Play, an internal testing track being sufficient.

If the app is sideloaded and Play cannot vouch for it, every call fails with
`PERMISSION_DENIED` until you either register the app with Play or set
`APP_CHECK_MODE=soft`. In `soft` mode the endpoints are protected by Firebase
Authentication alone — anyone who can obtain an anonymous Firebase token for the
project could call them, though they still could not read any relayed message
without the pair key held on the phones.

## Firestore rules

Still deployed through Firebase, and still free on Spark:

```
firebase deploy --project <project-id> --only firestore:rules
```

or the **Deploy Firestore Rules** GitHub workflow.
