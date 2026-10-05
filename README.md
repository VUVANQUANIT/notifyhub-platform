# NotifyHub

NotifyHub is a multi-tenant notification platform for creating campaigns and delivering email/SMS messages asynchronously.

The repository is structured as a distributed-system laboratory. The initial implementation focuses on service boundaries, messaging semantics, failure handling, and local reproducibility.

## Scope

The MVP covers:

- Tenant and user management.
- Role-based access control.
- Campaign lifecycle management.
- Recipient import and validation.
- Asynchronous email/SMS delivery.
- Delivery status and retry tracking.
- Per-tenant reporting.

The system does not call a real SMS provider in local development. MailHog is used as the local email sink; SMS delivery is represented by a provider adapter.

## Architecture

```text
                          Web Client
                              |
                        API Gateway :8080
                              |
        +---------------------+---------------------+
        |                     |                     |
  Auth Service          Campaign Service       Reporting Service
      :8081                   :8082                  :8084
        |                     |                     ^
        |                     |                     |
        |              Kafka domain events          |
        |                     |                     |
        |              RabbitMQ task queues         |
        |                     v                     |
        +-------------- Notification Service ------+
                              :8083
                          /          \
                       Redis       MailHog
```

### Service boundaries

| Service | Responsibility | Primary data |
|---|---|---|
| API Gateway | Routing and request-level cross-cutting concerns | None |
| Auth Service | Users, tenants, roles, permissions, token lifecycle | PostgreSQL |
| Campaign Service | Campaigns, recipients, scheduling, outbox records | PostgreSQL |
| Notification Service | Delivery workers, provider adapters, retry state | PostgreSQL, Redis |
| Reporting Service | Read model for delivery and campaign metrics | PostgreSQL |

Each service owns its schema. A single PostgreSQL container is used locally to reduce resource usage; this does not imply shared tables between services.

## Messaging design

Kafka and RabbitMQ are intentionally used for different message types.

### Kafka: domain events

Kafka is used for facts that may have multiple consumers and may need to be replayed:

- `campaign.created`
- `campaign.started`
- `notification.sent`
- `notification.failed`
- `member.created`

The event key is normally `tenantId` or `campaignId`. Ordering is guaranteed only within a partition. Reporting and audit consumers use separate consumer groups.

### RabbitMQ: delivery tasks

RabbitMQ is used for commands that should be processed by a worker:

- `notification.send.email`
- `notification.send.sms`
- `notification.retry`

The delivery worker uses manual acknowledgement, bounded prefetch, durable scheduled retries and dead-letter queues. A task is acknowledged after the provider result or a retry/dead-letter handoff commits to PostgreSQL.

### Campaign delivery flow

```text
Client
  -> Campaign Service: create campaign
  -> Campaign Service: import recipients and start campaign
  -> PostgreSQL: campaign state + outbox event in one transaction
  -> Kafka: CampaignStarted
  -> Campaign Dispatcher: create delivery tasks
  -> RabbitMQ: SendEmailTask / SendSmsTask
  -> Notification Service: execute task
  -> Kafka: NotificationSent / NotificationFailed
  -> Reporting Service: update read model
```

Kafka events represent facts that have occurred. RabbitMQ messages represent work that must be completed.

## Technology stack

| Area | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot, Spring Cloud Gateway |
| Security | Spring Security, JWT, refresh-token rotation |
| Persistence | PostgreSQL |
| Cache and control data | Redis |
| Event streaming | Spring Kafka, Kafka protocol |
| Local Kafka broker | Redpanda, single-node profile |
| Task messaging | Spring AMQP, RabbitMQ |
| Frontend | Angular, TypeScript, SCSS |
| Testing | JUnit, Testcontainers, WireMock |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry |
| Build and delivery | Maven, Docker Compose, GitHub Actions, Trivy |

## Repository layout

```text
notifyhub/
├── backend/
│   ├── api-gateway/
│   ├── auth-service/
│   ├── campaign-service/
│   ├── notification-service/
│   ├── reporting-service/
│   └── pom.xml
├── frontend/
├── infra/
│   └── prometheus.yml
├── docs/
│   └── adr/
├── .github/workflows/ci.yml
├── docker-compose.yml
├── .env.example
└── README.md
```

## Local development

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker Desktop

### Start infrastructure

```powershell
Copy-Item .env.example .env
docker compose up -d
```

RabbitMQ is enabled by default. Start the Kafka-compatible broker only when working on event streaming:

```powershell
docker compose --profile kafka up -d
```

### Build the backend

```powershell
mvn -f backend/pom.xml clean verify
```

### Local endpoints

| Component | Endpoint |
|---|---|
| API Gateway | `http://localhost:8080` |
| Auth Service | `http://localhost:8081/actuator/health` |
| Campaign Service | `http://localhost:8082/actuator/health` |
| Notification Service | `http://localhost:8083/actuator/health` |
| Reporting Service | `http://localhost:8084/actuator/health` |
| RabbitMQ Management | `http://localhost:15672` |
| Kafka-compatible broker | `localhost:19092` |
| Redpanda Admin API | `http://localhost:19644` |
| MailHog | `http://localhost:8025` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

## Delivery plan

### Stage 1 - Foundation

- [x] Maven multi-module build.
- [x] Service bootstraps and health endpoints.
- [x] Docker Compose infrastructure.
- [x] GitHub Actions Maven verification.
- [x] Kafka-compatible broker under the `kafka` profile.

### Stage 2 - Identity and tenant isolation

- [ ] Tenant, user, role and permission model.
- [ ] JWT access token and refresh-token rotation.
- [ ] Tenant context propagation through the request boundary.
- [ ] Database isolation tests.
- [ ] Redis-backed OTP and rate limiting.

### Stage 3 - Campaign API

- [x] Campaign state machine: `DRAFT`, `SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`.
- [x] Recipient CSV import and validation (bounded synchronous uploads).
- [x] Pagination, status filtering and idempotent create/start commands.
- [x] OpenAPI specification.
- [x] PostgreSQL persistence, Flyway migrations and Campaign tenant isolation.
- [x] Aggregate verified delivery results and finish campaigns after all recipients reach terminal state.

### Stage 4 - RabbitMQ delivery pipeline

- [x] Durable exchange and email/SMS queue declarations.
- [x] Publisher confirms and mandatory routing checks.
- [x] Manual acknowledgement and bounded prefetch.
- [x] Bounded provider retries with durable exponential backoff.
- [x] Confirmed dead-letter exchange and email/SMS dead-letter queues.
- [x] Delivery deduplication by `notificationId` after commit and across concurrent workers.
- [x] Email delivery through SMTP/MailHog and simulated SMS, with PostgreSQL delivery state.

### Stage 5 - Kafka event pipeline

- [x] Event envelope with `eventId`, `eventType`, `eventVersion`, `tenantId`, `occurredAt` and `correlationId`.
- [x] Topic and partition configuration; CampaignStarted dispatcher consumer.
- [x] Durable Notification delivery-result outbox and confirmed Kafka publisher.
- [x] Independent consumer groups for Campaign results and Reporting projections.
- [ ] Audit consumer group.
- [x] Dispatcher offset management, dead-letter topic and replay procedure.
- [x] Dispatcher handling for duplicate events and independence from earlier event arrival.

### Stage 6 - Consistency and operations

- [x] Transactional Outbox records and Kafka publisher in Campaign Service.
- [x] Durable batched dispatch jobs and confirmed RabbitMQ task publishing.
- [x] Reporting read model with tenant-scoped, paginated REST queries and JWT authorization.
- [ ] Metrics for throughput, consumer lag, retry count and failure rate.
- [ ] Distributed tracing across REST, Kafka and RabbitMQ.
- [x] Campaign API/persistence/security Testcontainers integration suite.
- [x] Container image build and Trivy scan configured in CI.
- [ ] Kubernetes deployment with k3d.

## Design constraints

- Default delivery guarantee is at-least-once; consumers must be idempotent.
- Ordering is scoped to a Kafka partition, not the entire system.
- A successful delivery is acknowledged after provider acceptance and database commit; a failed task is acknowledged after its retry/dead-letter handoff is durably committed.
- Business data and an outbox record must be committed in the same database transaction.
- A broker is selected according to message semantics, not convenience.
- Local infrastructure is split into profiles to keep laptop resource usage manageable.

## Architecture decisions

- [ADR-0001: Kafka for domain events and RabbitMQ for task delivery](docs/adr/0001-messaging-strategy.md)

## Domain documentation

- [Campaign flow, state machine and domain model](docs/domain/campaign-domain.md)
- [Testing strategy and TDD readiness](docs/testing-strategy.md)
- [Campaign Service: run locally, API and persistence](docs/campaign-service.md)
- [Campaign messaging: brokers, contracts, retries and replay](docs/campaign-messaging.md)
- [Delivery results, campaign completion and Reporting API](docs/delivery-results-reporting.md)
- [Project progress and remaining work](docs/project-progress.md)
- [Notification Worker: providers, delivery state, acknowledgement, retry and DLQ](docs/notification-worker.md)

## Project status

Campaign Service implements create, CSV import, listing, start/schedule and transactional outbox persistence. Its `messaging` profile publishes Kafka events, dispatches personalized RabbitMQ tasks and aggregates verified delivery results into COMPLETED/FAILED campaigns. Notification Service's `worker` profile sends email through SMTP/MailHog, simulates SMS and commits delivery state with durable retry/DLQ handoffs; its `events` profile publishes terminal results from a transactional outbox. Reporting's `messaging` profile maintains an idempotent PostgreSQL read model with tenant-scoped REST queries. Integration tests cover PostgreSQL, Kafka, RabbitMQ, MailHog, replay, failure recovery and JWT authorization. Local actor headers are development-only; production requires an external JWT issuer. Auth workflows, frontend, audit, business observability and Kubernetes operations remain pending. See the [progress assessment](docs/project-progress.md) for scope and evidence.
