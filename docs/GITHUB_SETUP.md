# GitHub automation setup

Phonemaster intentionally does not store Firebase credentials or Android signing keys in Git.

## Secret 1: GOOGLE_SERVICES_JSON_BASE64

Download `google-services.json` from the Firebase Android app whose package is `com.twophone.smsbridge`.

Convert the file to one-line Base64 and save the result as the repository secret:

`GOOGLE_SERVICES_JSON_BASE64`

On macOS/Linux:

```bash
base64 < google-services.json | tr -d '\n'
```

On PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("google-services.json"))
```

After this secret exists, `.github/workflows/ci.yml` can build a debug APK automatically.

## Firebase deployment

Create a Google Cloud service account for deployment with only the permissions required for your Firebase Functions/Firestore deployment. Download its JSON key, Base64-encode it, and store it as:

`FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`

Create the repository variable:

`FIREBASE_PROJECT_ID`

Then run the **Deploy Firebase Backend** workflow manually from GitHub Actions.

Protect the Google/Firebase administrator account with MFA. Rotate and revoke the service-account key if it is ever exposed.

## Signed release APK

Create your Android signing key locally. Never upload the raw keystore to GitHub. Add these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then manually run **Build Signed Release APK**.

The release build uses Firebase App Check's Play Integrity provider. Configure the release signing certificate SHA-256 and Play Integrity/App Check in Firebase before treating a release APK as production-ready.
