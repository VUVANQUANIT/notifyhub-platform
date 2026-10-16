# Audit, tracing and delivery recovery

## Audit

Auth writes `IdentityAuditEvent` v1 in the same transaction as successful registration, login/refresh/logout, member mutations, invitations, password recovery and MFA changes. Events contain tenant/actor/subject UUIDs, action and time; no email, password, token, OTP, MFA secret or message content. Enable the `audit` profile on Auth and Reporting together with Reporting `messaging`. Auth retries its durable outbox with capped backoff until Kafka accepts; it never deletes unpublished events. Published source events are retained 90 days. Kafka delivery is at least once and Reporting deduplicates by event ID plus canonical payload hash. Conflicting/invalid records follow the existing bounded retry and reporting DLT policy. This is an application audit trail, not tamper-proof regulatory storage; failed authentication attempts are not part of this success-event stream.

`GET /api/audit?page=0` through Gateway requires ADMIN (`users:read`) and returns 50 tenant-scoped entries per page. JWT expiry bounds stale-role access as with other downstream APIs; no local actor-header bypass exists for audit. Restrict database writes and Kafka ACLs to service identities in shared environments, and export audit data to immutable storage if required.

## Delivery recovery

Enable the Notification `operations` profile (with `worker`) and provision the same issuer/JWKS/audience as Gateway. Endpoints require ADMIN (`users:write`) directly at both Gateway and Worker:

- `GET /api/operations/dead-letters?page=0`: terminal failures with canonical durable dead-letter payloads and recovery IDs.
- `POST /api/operations/dead-letters/{notificationId}/redrive`: JSON `{requestId: UUID, reason: string}` with a reason of 5–500 characters; returns 202 `{notificationId}`.
- `GET /api/operations/redrives`: latest 100 recovery sends with actor, reason, parent ID and delivery state.

After repairing the provider/root cause, inspect the failure then explicitly redrive. Persist and reuse the same request ID/body after a lost response. One recovery is allowed per original delivery; a different request ID returns 409. Other tenants receive 404. Recovery uses a **new notification ID**, the original validated payload and the normal manual-ack/bounded-retry/dedup worker pipeline. It is tracked separately and does not change an already terminal campaign outcome or emit conflicting campaign/reporting results. Recovered failures are shown in operations history, so the original report remains an accurate historical result. Invalid/identity-conflict poison messages cannot be redriven through this API. Repeated recovery chains are intentionally blocked.

The database dead-letter handoff is the recovery source. The original RabbitMQ DLQ message remains as forensic evidence; the API does not purge or consume the shared queue. Apply the documented broker retention/archive policy after reviewing recovery history. SMTP/provider acceptance followed by a crash can still produce duplicates; the operator action itself is an intentional second send, not an exactly-once guarantee.

## Traces

Enable `tracing` on each service and set `OTEL_TRACES_ENDPOINT` (OTLP HTTP/protobuf, `/v1/traces`) and `TRACING_SAMPLE_RATE` (default 0.1). HTTP, Kafka template/listeners and Rabbit template/listeners use Micrometer/OpenTelemetry observation. Custom Kafka factories explicitly enable observation. Business correlation IDs remain available across durable outbox scheduling boundaries, which start separate traces; a single uninterrupted trace across committed outboxes is not claimed.

Local Tempo: `docker compose -f docker-compose.yml -f infra/compose-tracing.yml up -d tempo`. Bindings are loopback-only. Tempo retains 72 hours and is internal infrastructure without application authentication. Restrict it to operators. Add a Grafana Tempo datasource using `http://tempo:3200` for container access or loopback 3200 for host access. The implementation follows [Spring Boot tracing configuration](https://docs.spring.io/spring-boot/reference/actuator/tracing.html).

## External alerts

The opt-in `infra/alertmanager-webhook.yml` reads the HTTPS receiver URL and bearer token from `/run/secrets/alert_webhook_url` and `/run/secrets/alert_webhook_token`. Mount both files and use that config instead of the local sink. Receiver must accept Alertmanager webhook v4 payloads. Do not put secrets in the YAML or command history. `send_resolved` is enabled; alerts are grouped and repeated at four hours. Verify a synthetic alert reaches the receiver before relying on it. No real external alert has been sent without configured receiver credentials.
