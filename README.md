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

The delivery worker uses manual acknowledgement, bounded prefetch, retry queues and a dead-letter queue. A task is acknowledged only after the provider adapter returns a successful result.

### Campaign delivery flow

```text
Client
  -> Campaign Service: create campaign
  -> PostgreSQL: campaign + outbox event in one transaction
  -> Kafka: CampaignCreated
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

- [ ] Campaign state machine: `DRAFT`, `SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`.
- [ ] Recipient import and validation.
- [ ] Pagination, filtering and idempotent commands.
- [ ] OpenAPI specification.

### Stage 4 - RabbitMQ delivery pipeline

- [ ] Exchange and queue declarations.
- [ ] Publisher confirms.
- [ ] Manual acknowledgement and prefetch.
- [ ] Retry with backoff.
- [ ] Dead-letter exchange and dead-letter queue.
- [ ] Idempotent delivery by `notificationId`.

### Stage 5 - Kafka event pipeline

- [ ] Event envelope with `eventId`, `eventType`, `eventVersion`, `tenantId`, `occurredAt` and `correlationId`.
- [ ] Topic and partition configuration.
- [ ] Consumer groups for reporting and audit.
- [ ] Offset management and replay procedure.
- [ ] Consumer handling for duplicate and out-of-order events.

### Stage 6 - Consistency and operations

- [ ] Transactional Outbox in Campaign Service.
- [ ] Reporting read model.
- [ ] Metrics for throughput, consumer lag, retry count and failure rate.
- [ ] Distributed tracing across REST, Kafka and RabbitMQ.
- [ ] Testcontainers integration suite.
- [ ] Container image build and Trivy scan in CI.
- [ ] Kubernetes deployment with k3d.

## Design constraints

- Default delivery guarantee is at-least-once; consumers must be idempotent.
- Ordering is scoped to a Kafka partition, not the entire system.
- A message is not acknowledged before its side effect is completed.
- Business data and an outbox record must be committed in the same database transaction.
- A broker is selected according to message semantics, not convenience.
- Local infrastructure is split into profiles to keep laptop resource usage manageable.

## Architecture decisions

- [ADR-0001: Kafka for domain events and RabbitMQ for task delivery](docs/adr/0001-messaging-strategy.md)

## Project status

The repository currently contains the service skeleton, local infrastructure, build configuration and architecture documentation. Business workflows, producers, consumers and persistence models are implemented incrementally according to the delivery plan above.
