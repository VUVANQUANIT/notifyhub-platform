# Real SMTP and Twilio SMS

Local defaults remain MailHog and the deterministic SMS simulator. Real adapters are explicit configuration choices, with no automatic fallback to simulated success. Runtime settings, usernames/API keys, passwords and sender identities belong in an external secret store or ignored local secret files, never in Git or PR bodies.

## SMTP

Add the `providers` profile to Auth and/or Notification, preserving their current profiles. Auth uses `AUTH_SMTP_HOST/PORT/USERNAME/PASSWORD` and `AUTH_MAIL_FROM`; Notification uses `SMTP_HOST/PORT/USERNAME/PASSWORD` and `MAIL_FROM`. Port defaults to 587. The profile requires SMTP authentication, mandatory STARTTLS and CA/hostname validation. Startup rejects missing settings or a security downgrade. Use a sender/domain configured with your email provider. Implicit-TLS port 465 is not this profile; do not disable TLS validation to work around a certificate error.

Auth invitation/recovery emails continue using their encrypted durable outboxes. Notification email uses the existing manual-ack delivery pipeline. Invalid message construction and authentication rejection are terminal; transient connection/send errors retain bounded retry/DLQ. Persisted errors are safe codes, not provider text. SMTP acceptance does not prove mailbox delivery, and a crash after acceptance but before DB commit can resend a message.

## Twilio

Set `SMS_PROVIDER=twilio` with the `worker` profile and provision:

- `TWILIO_ACCOUNT_SID` (AC...), `TWILIO_API_KEY_SID` (SK...) and `TWILIO_API_KEY_SECRET`.
- Exactly one `TWILIO_MESSAGING_SERVICE_SID` (MG...) or `TWILIO_FROM` (E.164 sender).

The adapter calls the fixed HTTPS [Twilio Messages endpoint](https://www.twilio.com/docs/messaging/api/message-resource) using API-key Basic authentication, UTF-8 form encoding and a 1600-code-point body bound. It follows no redirects, caps response data at 64 KiB, limits connect time to 3 seconds and the full exchange to 10 seconds, cancelling timed-out requests. It never logs credentials, destination, text or raw error responses.

HTTP 429/408/5xx and transport failures are retryable; numeric `Retry-After` is honored subject to the worker maximum delay and total attempt limit. Authentication/other client rejection is terminal. A malformed/oversized successful response requires operator review, since the provider may already have accepted the SMS. `SENT` means Twilio accepted the message (reference SM...), not a verified handset delivery receipt. Delivery-status callbacks and carrier receipt projection are not implemented or claimed. Sender/account setup belongs to the provider account; no account or phone number is invented by the application.

Keep `SMS_PROVIDER=simulated` explicitly for local validation. Unknown provider names or incomplete Twilio credentials fail startup; selecting Twilio never silently falls back to simulated delivery. Network/commit failures can still duplicate a charged provider operation. Use the audited one-off recovery API only after investigating provider state.

## Kubernetes configuration

Copy `infra/providers/auth.env.example` and `notification.env.example` to ignored `.env.providers/` files, replace every placeholder, then validate and apply separately:

```powershell
./scripts/configure-providers.ps1 -Service auth -EnvFile .env.providers/auth.env -ValidateOnly
./scripts/configure-providers.ps1 -Service notification -EnvFile .env.providers/notification.env -ValidateOnly
./scripts/configure-providers.ps1 -Service auth -EnvFile .env.providers/auth.env
./scripts/configure-providers.ps1 -Service notification -EnvFile .env.providers/notification.env
```

The helper accepts only provider fields, preserves existing DB/broker secrets, applies provider fields through stdin using server-side apply (Base64 `data`, no plaintext last-applied annotation), enables mandatory-TLS email profiles and restarts the selected deployment. It opens only provider egress ports 587/443 for Auth/Notification. In shared environments, narrow destinations through your egress gateway/provider policy and supply your kubeconfig/namespace explicitly. No SMS or email is sent by the configuration script. Verify rollout health, then send a deliberately chosen test campaign after the operator has configured the intended recipient.

Set `AUTH_PUBLIC_URL` to the reachable HTTPS frontend origin for externally emailed invitations. The local `notifyhub.localhost` URL is only useful on the local machine. Configure a real certificate/domain before exposing the application to users.

## Validation and activation boundary

Protocol tests use a local HTTP server rather than a real Twilio account: API-key/form handling, acceptance references, 429/server/authentication failures, malformed/oversized responses and invalid settings. Worker integration verifies a provider rate limit persists as a capped durable retry, succeeds later and deduplicates replay. SMTP tests cover security downgrade rejection and redacted authentication failure. Existing MailHog/RabbitMQ/Kafka integration and HTTPS/k3d smoke validate the unchanged local flow.

Real SMTP/Twilio sending and external Alertmanager delivery require account credentials, sender/domain and receiver provisioning. They have not been performed using fabricated credentials or unknown recipients. The code/configuration path is ready; production activation and real-provider acceptance testing remain explicit operator steps.
