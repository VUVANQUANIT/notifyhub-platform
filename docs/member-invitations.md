# Member invitations

ADMIN opens Members → Mời thành viên qua email. Invitations grant the selected tenant role only after the recipient opens the email and sets a password. Pending invitations are separate from enabled members and never count toward the last-admin rule.

- `POST /api/auth/users/invitations`: `{email, displayName, role}` → 202 with invitation ID only. Existing users return 409. Reissuing an email revokes the previous link.
- `GET /api/auth/users/invitations`: latest 100 tenant invitations, lifecycle and mail state, no token.
- `DELETE /api/auth/users/invitations/{id}`: revoke an unaccepted invitation. Cross-tenant IDs return 404.
- `POST /api/auth/invitations/accept`: public JSON `{token,password}` → 204. Password follows the existing minimum 15 code-point policy. Expired, revoked or consumed links return generic 401.

Set `AUTH_PUBLIC_URL` to the HTTPS frontend origin. Loopback HTTP is accepted for development. Do not derive links from request Host headers. Links contain a 256-bit random token in the URL fragment; the frontend removes it immediately and never persists it. Only SHA-256 is stored in the invitation row. The SMTP outbox encrypts the token with the provisioned Auth control secret, wipes it after terminal processing and retries at most four times. Link validity is 24 hours and enforced in PostgreSQL independently of SMTP status. SMTP acceptance does not guarantee mailbox delivery; a crash after SMTP acceptance may resend the same link, but cannot activate twice.

Acceptance locks tenant and invitation in a transaction, inserts the member and records `email_verified_at`, then consumes the link. GET and email scanners cannot activate accounts. The user signs in normally afterwards with the tenant slug and email supplied in the invitation. Existing/directly-created accounts remain supported and are not retroactively marked email-verified.

Verification: Auth integration tests cover real MailHog SMTP, encrypted payload disposal, bounded retry, single-use/concurrent acceptance, replacement/revocation/expiry, disabled tenants, tenant isolation and live ADMIN checks. Chromium covers public activation, fragment removal, credential clearing and error handling.
