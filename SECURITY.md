# Phonemaster security notes

No application can be guaranteed impossible to compromise. Phonemaster uses layered controls intended to reduce the impact of common mobile, backend, and credential attacks.

## Trust boundaries

Phone A sees SMS plaintext because Android delivers the message to the explicitly permitted receiver. Before leaving Phone A, sender/body/timestamp are encrypted with the pair key. Firebase stores only the encrypted payload plus routing metadata. Phone B decrypts only after local authentication when viewing history.

## Key protection

The pair key is random 256-bit material. Its persistent local copy is encrypted with an AES key generated in Android Keystore. StrongBox is requested on supported devices with a normal Keystore fallback when unavailable. Pairing material should be treated like a password and transferred only between the two intended phones.

## Backend authorization

Every callable function requires Firebase Authentication and Firebase App Check. Backend operations verify whether the authenticated installation is the pair owner or receiver before reading or mutating pair records. Direct Firestore mobile access is denied by rules.

## Abuse controls

Pair codes expire, failed joins are capped, source relay volume is rate-limited, and relay IDs make retries idempotent. Messages contain an expiry timestamp for seven-day ciphertext retention; enable Firestore TTL on `expiresAt` in the Firebase console so expired records are physically deleted automatically.

## Device privacy

Decrypted message bodies are not stored in the local SQLite history. Notifications are redacted. The main activity uses Android `FLAG_SECURE`. Phone B requires strong biometric authentication or the device credential to display decrypted history.

## Repository secrets

Never commit `google-services.json`, Firebase service-account JSON, signing keystores, private keys, passwords, or App Check debug tokens. GitHub Actions expects these through repository secrets as documented in `docs/GITHUB_SETUP.md`.

## Recommended account controls

Enable MFA on GitHub, Google/Firebase, and the primary email account. Use a least-privilege Firebase deployment service account and rotate any credential that may have been exposed. Keep Android and dependencies updated, and review Dependabot pull requests before merging.
