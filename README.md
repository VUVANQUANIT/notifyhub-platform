# NotifyHub - Microservice Messaging Lab

NotifyHub là một nền tảng SaaS quản lý campaign gửi thông báo đa kênh cho nhiều tenant. Đây là project portfolio và phòng lab DevOps/messaging, không chỉ là một ứng dụng CRUD.

Mục tiêu chính của project là học cách một hệ thống backend production vận hành: phân quyền, tenant isolation, giao tiếp đồng bộ, giao tiếp bất đồng bộ, retry, idempotency, observability, CI/CD và triển khai local trên một laptop.

## Vì sao chọn bài toán này?

Bài toán nối trực tiếp với kinh nghiệm trong CV:

- RBAC và multi-tenant trong hệ thống enterprise.
- SMS/email, report generation và tracking lifecycle.
- Redis cho OTP, cache, rate limit và idempotency.
- RabbitMQ cho xử lý task bất đồng bộ.
- Kafka cho domain event, replay và read model báo cáo.
- Angular cho dashboard quản trị.

## Mục tiêu học tập

Sau khi hoàn thành, ông có thể giải thích và demo được:

1. Khi nào dùng REST, khi nào dùng RabbitMQ, khi nào dùng Kafka.
2. Consumer group, partition, offset, key và ordering trong Kafka.
3. Exchange, routing key, queue, acknowledgement, prefetch và dead-letter trong RabbitMQ.
4. Vì sao cần Outbox Pattern để tránh lỗi ghi database thành công nhưng publish message thất bại.
5. Retry, backoff, idempotency và xử lý duplicate message.
6. Metrics, logs, traces và cách điều tra một message bị chậm hoặc thất bại.
7. Build, test, security scan và release bằng GitHub Actions.
8. Chạy hệ thống trên Docker Compose trước khi học Kubernetes.

## Kiến trúc tổng thể

```text
                         Angular Dashboard
                                |
                           API Gateway
                                |
        +-----------------------+-----------------------+
        |                       |                       |
   Auth Service          Campaign Service        Reporting Service
        |                       |                       ^
   PostgreSQL                  |                       |
                               |                       |
                 +-------------+-------------+         |
                 |                           |         |
           Kafka events                RabbitMQ tasks  |
           (replayable)                (work queues)   |
                 |                           |         |
                 |                    Notification     |
                 +------------------> Service ---------+
                                      |        |
                                    Redis    MailHog
```

## Quyết định dùng Kafka và RabbitMQ

Không dùng hai broker cho cùng một việc. Mỗi broker có một vai trò để ông học được sự khác nhau.

### Kafka: event backbone

Kafka lưu event theo topic/partition và consumer có thể đọc lại event theo offset. Dùng Kafka cho những sự kiện có nhiều subscriber hoặc cần replay:

- `campaign.created`
- `campaign.started`
- `notification.sent`
- `notification.failed`
- `member.created`

Consumer groups dự kiến:

- `reporting-service`: xây read model và dashboard thống kê.
- `audit-service`: ghi audit trail.
- `analytics-service`: phase nâng cao.

Partition key nên là `tenantId` hoặc `campaignId` để giữ ordering trong phạm vi cần thiết. Không hứa hẹn total ordering cho toàn bộ hệ thống.

### RabbitMQ: command/task delivery

RabbitMQ phù hợp với task cần giao cho worker và xác nhận hoàn thành:

- `notification.send.email`
- `notification.send.sms`
- `notification.retry`

Notification Service nhận task từ queue, gọi MailHog/provider, `ack` khi thành công và đưa message lỗi sang retry/DLQ khi thất bại. Dùng `prefetch` để worker không bị quá tải.

### Luồng gửi campaign

```text
1. POST /api/campaigns
2. Campaign Service lưu campaign + outbox event trong PostgreSQL
3. Outbox Publisher publish CampaignCreated vào Kafka
4. Campaign Dispatcher tạo các NotificationTask vào RabbitMQ
5. Notification Service xử lý task và gửi email/SMS
6. Notification Service publish NotificationSent/Failed vào Kafka
7. Reporting Service consume event và cập nhật read model
8. Angular đọc trạng thái từ Reporting Service
```

Điểm cần nhớ: Kafka event thể hiện **điều đã xảy ra**; RabbitMQ message thể hiện **việc cần làm**.

## Công nghệ

| Nhóm | Công nghệ | Mục đích |
|---|---|---|
| Backend | Java 21, Spring Boot | Service implementation |
| API edge | Spring Cloud Gateway | Routing, correlation ID, rate limit về sau |
| Auth | Spring Security, JWT, refresh token rotation | Authentication và RBAC |
| Database | PostgreSQL | Transactional data, outbox, read model |
| Cache | Redis | OTP, cache, rate limit, idempotency key |
| Task broker | RabbitMQ + Spring AMQP | Worker tasks, retry, DLQ |
| Event broker | Kafka-compatible Redpanda + Spring Kafka | Domain events, replay, consumer groups |
| Email local | MailHog | Không cần provider thật khi development |
| Frontend | Angular, TypeScript, SCSS | Admin dashboard |
| Test | JUnit, Testcontainers, WireMock | Unit, integration, contract-like tests |
| Observability | Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry | Health, metrics, traces |
| Packaging | Docker, Docker Compose | Local environment |
| CI/CD | GitHub Actions, Trivy | Verify, image build, vulnerability scan |
| Kubernetes | k3d hoặc kind | Chỉ học sau khi Compose ổn |

## Repository layout

```text
notifyhub/
├─ backend/
│  ├─ api-gateway/
│  ├─ auth-service/
│  ├─ campaign-service/
│  ├─ notification-service/
│  ├─ reporting-service/
│  └─ pom.xml
├─ frontend/
├─ infra/
│  └─ prometheus.yml
├─ docs/
│  └─ adr/
├─ .github/workflows/ci.yml
├─ docker-compose.yml
├─ .env.example
└─ README.md
```

## Chạy local trên laptop

Yêu cầu: JDK 21, Maven 3.9+, Docker Desktop.

```powershell
Copy-Item .env.example .env
docker compose up -d
mvn -f backend/pom.xml clean verify
```

RabbitMQ chạy mặc định. Kafka-compatible broker chạy bằng profile riêng để tiết kiệm RAM:

```powershell
docker compose --profile kafka up -d
```

Khi chỉ code Auth hoặc Campaign không cần message broker, có thể bật từng hạ tầng cần thiết thay vì chạy tất cả.

| Service | URL | Mục đích |
|---|---|---|
| PostgreSQL | `localhost:5432` | Transactional database |
| Redis | `localhost:6379` | Cache và control data |
| RabbitMQ | `localhost:5672` | Task broker |
| RabbitMQ UI | `http://localhost:15672` | Queue/exchange monitoring |
| Kafka-compatible broker | `localhost:19092` | Event streaming |
| Redpanda admin | `http://localhost:19644` | Broker health/admin |
| MailHog UI | `http://localhost:8025` | Xem email local |
| Prometheus | `http://localhost:9090` | Metrics |
| Grafana | `http://localhost:3000` | Dashboard |

## Lộ trình triển khai theo phase

### Phase 0 - Skeleton và local infrastructure

- [x] Maven multi-module và năm service chạy được.
- [x] Docker Compose cho PostgreSQL, Redis, RabbitMQ, MailHog, Prometheus, Grafana.
- [ ] Bổ sung Kafka-compatible broker profile.
- [ ] Viết ADR đầu tiên về lựa chọn broker.

### Phase 1 - Auth và tenant isolation

- [ ] User, Tenant, Role, Permission.
- [ ] JWT access token và refresh token rotation.
- [ ] Mọi bảng nghiệp vụ có `tenant_id`.
- [ ] Test không thể đọc dữ liệu tenant khác.
- [ ] Redis OTP và rate limiting.

### Phase 2 - Campaign CRUD và REST boundary

- [ ] Campaign lifecycle: `DRAFT`, `SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`.
- [ ] Recipient import từ CSV.
- [ ] Validation, pagination, filtering và unified error response.
- [ ] Correlation ID xuyên qua Gateway và các service.

### Phase 3 - RabbitMQ work queue

- [ ] Exchange, queue, routing key cho email/SMS task.
- [ ] Publisher confirm.
- [ ] Manual acknowledgement và prefetch.
- [ ] Retry queue với backoff.
- [ ] Dead-letter exchange/queue.
- [ ] Idempotency theo `notificationId`.

### Phase 4 - Kafka event streaming

- [ ] Topic, partition, replication setting cho local profile.
- [ ] Producer gửi domain event bằng Spring Kafka.
- [ ] Consumer group cho Reporting và Audit.
- [ ] Offset commit, consumer restart và replay event.
- [ ] Partition key theo `tenantId`/`campaignId`.
- [ ] Schema version trong event envelope.

### Phase 5 - Outbox và consistency

- [ ] Outbox table trong Campaign Service.
- [ ] Scheduled publisher hoặc CDC-style polling.
- [ ] Đảm bảo event không mất khi transaction commit.
- [ ] Xử lý duplicate event ở consumer.
- [ ] ADR về at-least-once delivery.

### Phase 6 - Reporting và observability

- [ ] Read model riêng cho dashboard.
- [ ] Actuator health/readiness/liveness.
- [ ] Micrometer metrics cho throughput, lag, retry và failure rate.
- [ ] Trace REST -> Kafka/RabbitMQ -> worker.
- [ ] Grafana dashboard và runbook điều tra lỗi.

### Phase 7 - Delivery

- [ ] Testcontainers cho PostgreSQL, Redis, RabbitMQ và Kafka.
- [ ] GitHub Actions verify/test.
- [ ] Build container images.
- [ ] Trivy scan.
- [ ] Deploy local bằng k3d.
- [ ] Chỉ sau đó mới cân nhắc free-tier cloud.

## Nguyên tắc để học được nhiều

- Không dùng Kafka và RabbitMQ một cách trùng lặp.
- Mỗi phase phải có demo, test và một ADR.
- Không chạy cả hệ thống 24/7 trên laptop; bật broker theo profile.
- Không thêm service mới nếu chưa chứng minh được boundary của service hiện tại.
- Không gọi hệ thống là "exactly once" nếu chưa giải thích được transaction và idempotency.
- Không dùng database chung cho mọi service trong production design; local có thể dùng một PostgreSQL instance nhưng mỗi service nên có schema/database ownership riêng.

## Tiêu chí portfolio

README cuối cùng phải có architecture diagram, sequence diagram, API examples, cách chạy local, test strategy, failure scenarios, dashboard screenshot và một mục "trade-offs". Người xem repository phải thấy được lý do chọn công nghệ, không chỉ thấy danh sách công nghệ.

## Tài liệu tham khảo

- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [RabbitMQ Tutorials](https://www.rabbitmq.com/tutorials)
- [RabbitMQ AMQP Concepts](https://www.rabbitmq.com/tutorials/amqp-concepts)
