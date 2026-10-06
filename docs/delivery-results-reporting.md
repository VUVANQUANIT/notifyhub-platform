# Delivery results and Reporting

## Local flow

Build all modules before launching a service; `event-contracts` is a small Java library shared by the three services.

```powershell
rtk docker compose --profile kafka up -d postgres rabbitmq mailhog redpanda
rtk proxy cmd.exe /d /c backend\mvnw.cmd -f backend\pom.xml --batch-mode --no-transfer-progress clean verify
```

Run each command in a separate terminal from the repository root:

```powershell
rtk proxy java -jar backend/campaign-service/target/campaign-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,messaging
rtk proxy java -jar backend/notification-service/target/notification-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,worker,events
rtk proxy java -jar backend/reporting-service/target/reporting-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,messaging
```

Create/import/start a campaign as described in [Campaign Service](campaign-service.md). After delivery, its Campaign API status becomes terminal and Reporting exposes the result. The `events` profile enables Notification's Kafka publisher; without that profile results remain durable and pending in its outbox. RabbitMQ worker retry and acknowledgement do not depend on Kafka availability.

```text
Notification: provider result + delivery state + result_outbox (one DB transaction)
  -> Kafka notifyhub.notification.events.v1 (key = campaignId)
  -> Campaign group campaign-results-v1: verified task identity + delivery_results + completion outbox
  -> Reporting group reporting-deliveries-v1: idempotent delivery projection

CampaignStarted / CampaignCompleted / CampaignFailed
  -> Kafka notifyhub.campaign.events.v1
  -> Reporting group reporting-campaigns-v1: campaign status + expected recipients
```

All three services own separate schemas; there are no cross-service SQL reads, foreign keys or joins.

## Result contract v1

Envelope: `eventId`, `eventType`, `eventVersion`, `tenantId`, `campaignId`, `occurredAt`, `correlationId`, `payload`.

Payload: `notificationId`, `recipientId`, `channel`, `status`, `attempts`, `providerReference`, `failureCode`.

- `NotificationSent`: `status=SENT`, provider reference present, failure code null.
- `NotificationFailed`: `status=FAILED`, safe failure code present, provider reference null.
- No event is emitted for intermediate provider retries, rejected JSON or conflicting task identities.
- Destination, message subject/body and provider exception text are excluded.
- Event ID and content are persisted once per notification; retry publishing preserves both. One notification has one terminal result.
- Flyway V2 backfills existing terminal deliveries when upgrading Notification. Pending retries emit their result when they finish. Existing `CampaignStarted` events without `expected` are accepted by Reporting with an unknown expected count; a later completion event supplies it.

`CampaignStarted` now includes `expected`. Completion events include `status`, `expected`, `sent`, `failed` and preserve the start correlation ID. Campaign counts against its immutable recipient list and verifies result notification/recipient/tenant/campaign/channel/correlation against its own task snapshot. Unknown tasks and conflicting results are rejected.

## Completion rules and recovery

Campaign stays `RUNNING` until dispatch has completed **and every expected recipient has a terminal result**. All sent means `COMPLETED`; any failure means `FAILED`, after remaining recipients finish. Completion and its outbox event commit together. A sweep covers results arriving before the last dispatch batch commits.

Notification publisher locks one pending outbox row with `FOR UPDATE SKIP LOCKED`, waits for Kafka acknowledgement, then marks it published. A broker error retains the row and retries with 1/2/4/... seconds capped at 60. Broker retries do not consume provider attempts and do not discard a delivery result after an arbitrary number of publish failures.

Consumers commit offsets after their projection transaction returns. Duplicate event/notification IDs do not inflate counters. Reporting handles delivery before start and completion before start, and does not regress terminal status. Conflicting identities, counts or terminal outcomes are quarantined. Different topics/groups are independent: report status can arrive before its delivery counters; this is eventual consistency, and `pending` tracks results still absent from the projection rather than provider work still in flight. `expected`/`pending` are null until the expected count is known.

Each consumer retries twice with one-second backoff, then confirms a DLT publish before advancing the original offset:

| Consumer | Dead-letter topic |
|---|---|
| Campaign results | `notifyhub.notification.events.v1.campaign.dlt` |
| Reporting deliveries | `notifyhub.notification.events.v1.reporting.dlt` |
| Reporting campaign progress | `notifyhub.campaign.events.v1.reporting.dlt` |

Fix the underlying problem, replay the original JSON to its original topic with `campaignId` as key, and preserve its event ID and correlation ID. DLT processing is an operator responsibility; an automated redrive/admin API is not provided. If one campaign's result is missing or quarantined, it remains `RUNNING` instead of pretending that delivery completed.

Guarantees remain at-least-once. Kafka acknowledgement followed by a DB rollback can produce duplicate events. SMTP acceptance followed by a DB rollback can produce duplicate email. `SENT` means provider acceptance, not delivery to a recipient mailbox.

## Reporting API

All routes are GET and tenant scoped:

| Route | Result |
|---|---|
| `/api/reports/summary` | Observed terminal deliveries, sent, failed, total provider attempts |
| `/api/reports/campaigns?page=0&size=20` | Paginated campaign summaries |
| `/api/reports/campaigns/{id}` | Status, expected recipients, sent, failed, pending and attempts |
| `/api/reports/campaigns/{id}/deliveries?status=FAILED&page=0&size=20` | Paginated delivery IDs, recipient IDs, channels, safe failure codes, timestamps |

Page is non-negative; size is 1..100. Delivery filter accepts `SENT`/`FAILED`. A missing report or another tenant's campaign returns 404. API results do not expose destination, message content or provider reference. Reporting creates a read model only for started/delivered campaigns, not a second authoritative Campaign database.

Local profile binds loopback and accepts UUID `X-Tenant-Id`/`X-User-Id` headers. Outside local, a valid JWT with UUID subject, UUID `tenant_id` and scope `reports:read` is required; tenant headers are ignored. Configure `REPORTING_DB_URL`, `REPORTING_DB_USERNAME`, `REPORTING_DB_PASSWORD`, Kafka connectivity, `JWT_ISSUER_URI`, `JWT_AUDIENCE` and optionally `JWT_JWK_SET_URI` for `prod,messaging`. Do not combine local with prod or authenticated-local. Repository Auth now supplies the JWT/JWKS issuer, tenant/user roles and refresh lifecycle; [the Auth runbook](auth-service.md) explains authenticated-local and Gateway integration.

Example against local services:

```powershell
$headers = @{ 'X-Tenant-Id'='<tenant UUID>'; 'X-User-Id'='<user UUID>' }
Invoke-RestMethod http://127.0.0.1:8084/api/reports/summary -Headers $headers
Invoke-RestMethod http://127.0.0.1:8084/api/reports/campaigns -Headers $headers
```

SQL observation inside each service's database:

```sql
SELECT event_id, notification_id, publish_attempts, last_error FROM notification.result_outbox WHERE published_at IS NULL;
SELECT campaign_id, status, count(*) FROM campaign.delivery_results GROUP BY campaign_id, status;
SELECT tenant_id, campaign_id, status, expected FROM reporting.campaign_reports;
```

## Verification

`clean verify` runs domain rules, shared contracts, PostgreSQL migration/backfill, Kafka outage/ack rollback, result replay/concurrency, partial failure, dispatch-completion race, projections, invalid-event DLT, pagination, tenant isolation and signed JWT authorization tests. Docker is required and these integration tests are not skipped.

With all three local services running, the repeatable smoke creates a fresh tenant's email and SMS campaigns, imports CSV, waits for Campaign and Reporting completion, checks MailHog and verifies another tenant gets 404. It leaves sample campaigns/messages for inspection and does not delete existing application data:

```powershell
rtk proxy powershell -NoProfile -File scripts/smoke-delivery.ps1
```

For isolated smoke infrastructure, `scripts/smoke-compose.yml` uses PostgreSQL 15432, RabbitMQ 15673, Kafka 19093, SMTP 11025 and MailHog 18025, without persistent volumes. Launch services with matching datasource/broker/mail properties on ports 18082/18083/18084, then supply `-CampaignUrl http://127.0.0.1:18082 -ReportingUrl http://127.0.0.1:18084 -MailHogUrl http://127.0.0.1:18025` to the smoke script. Cleanup: `rtk docker compose -f scripts/smoke-compose.yml down` after stopping those service processes.

To verify bounded failure on that isolated stack, stop its MailHog service and rerun with `-ExpectEmailFailure`: email must become FAILED with `SmtpUnavailable`, SMS still completes, and Reporting must show the same outcomes. Restart MailHog afterward. The script itself never stops infrastructure.
