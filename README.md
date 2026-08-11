# Phonemaster

Phonemaster is a personal two-phone Android SMS relay for devices you explicitly own and pair. Phone A holds the SIM; Phone B receives encrypted copies of new SMS messages.

## Security model

- Explicit one-time setup and Android SMS permission on Phone A.
- 256-bit AES-GCM end-to-end encryption; the relay stores ciphertext, not SMS plaintext.
- Pair keys are wrapped locally with Android Keystore, using StrongBox when available.
- Firebase callable functions require Firebase Authentication and App Check.
- Release builds use Play Integrity App Check; debug builds use Firebase's debug provider for development only.
- Pairing codes expire after 10 minutes and lock after repeated failures.
- Relay requests are idempotent and rate-limited.
- Phone B stores encrypted message records and requires biometric/device-credential authentication before displaying plaintext history.
- Lock-screen notifications do not contain SMS/OTP text.
- `FLAG_SECURE` blocks ordinary screenshots/screen recording of the app activity.
- Firestore client access is deny-all; application access is server-mediated.
- A paired device can revoke the relationship and clear local state.

## Android limitation

Android platform protections can restrict access to certain OTP-formatted SMS messages on newer Android versions. Phonemaster does not bypass Android permissions or OTP protections.

## Firebase setup

Register Android package `com.twophone.smsbridge`, enable Anonymous Authentication, create Firestore, enable Cloud Functions/FCM, and configure Firebase App Check. Never commit `google-services.json` or service-account credentials.

## GitHub automation

The repository contains workflows for backend validation, debug APK builds, signed release APK builds, and manual Firebase deployment. See `docs/GITHUB_SETUP.md` for the secrets/variables required.

## Two-phone setup

1. Install the same APK on both phones.
2. On Phone A, choose **Phone A — SIM phone**, grant SMS permission, and create a pairing bundle.
3. Transfer the one-time bundle directly to Phone B and pair it before the server-side code expires.
4. Grant notification permission on Phone B.
5. After pairing, the app does not need to remain open for normal SMS relay operation.

This project intentionally excludes stealth installation, hidden permission granting, Android security bypasses, and covert remote control.
