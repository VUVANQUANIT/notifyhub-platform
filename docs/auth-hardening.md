# Account hardening

## MFA

Open Bảo mật tài khoản from the workspace. Re-enter the password, add the displayed Base32 secret to a TOTP authenticator, then confirm a six-digit code within ten minutes. There is no QR service or external secret transfer. Confirmation returns eight random recovery codes once; save them outside the application. Secrets remain encrypted using the provisioned control secret; only recovery-code hashes are stored. Authenticated endpoints are `/api/auth/mfa`, `/enrollment`, `/confirm`, `/disable` (JSON POST for writes).

Login accepts optional `code`: six-digit TOTP or a 43-character recovery code. Accounts without MFA still use the normal password flow. TOTP implements [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238), SHA-1/6 digits/30 seconds with one step of skew on either side. Successfully consumed steps cannot be replayed, including the enrollment code; wait for a new code before logging in. Concurrent operations lock tenant then user. Recovery codes are single-use and consumed in the same transaction as session creation. Password reset does not disable MFA. Losing both the authenticator and all recovery codes requires an out-of-band identity recovery procedure; there is deliberately no password-only MFA bypass.

Enabling/disabling revokes existing refresh sessions. Short-lived access JWTs remain valid through their expiry/skew as with other identity mutations. Rate controls apply to MFA changes and login. Disabled tenants/users cannot enroll or verify. MFA is opt-in, not yet an organization-wide enforcement policy.

## Signing-key rotation

The active private PKCS8 key remains `AUTH_SIGNING_KEY_PATH`. `AUTH_PREVIOUS_PUBLIC_JWKS_PATH` optionally supplies an overlap JWK set containing **only public** RS256 RSA signing keys with fingerprint key IDs. Both the Auth decoder and public JWKS support overlap keys; new tokens use only the active key. Invalid, weak or private overlap keys fail startup.

1. Generate a new private key outside the app and export its public JWK. Keep both secrets out of Git. Stage the new public JWK as overlap on every Auth replica while continuing to sign with the old key.
2. Roll Auth replicas and verify their JWKS all advertise both keys. Wait for the maximum downstream JWKS cache age configured in your deployment.
3. Switch the active private key to the new key and overlap to the old public key on every replica; roll again. Verify a new JWT `kid`, public JWKS, Gateway and downstream requests.
4. Wait until the final old-key token has expired, plus configured clock skew and cache overlap. Remove the old public key and roll again. Retain the old private key only according to the incident/rollback policy; never place it in the overlap file.

Emergency key compromise requires removing the public key from all validators/caches and restarting/invalidating caches, plus session revocation; ordinary overlap intentionally continues trusting old signatures. Rotation uses provisioned files and rolling restarts rather than a secret upload API.

## Retention

Hourly cleanup (`auth.retention.enabled`, `auth.retention.interval-ms`) processes at most 500 records per statement. Expired refresh sessions and their tokens are removed only after absolute expiry, preserving live-family replay detection. Expired pending codes/invitations are wiped, terminal recovery history and invitations expire after 30 days, and expired pending MFA setup secrets are cleared. Pending recovery notices are retained until sent/failed. Rows that remain after a batch are processed on later ticks. Database failures leave cleanup retryable; monitor database health/backlogs.
