# Notification Worker

## Chạy local

Từ repository, bật PostgreSQL, RabbitMQ và MailHog; thêm Redpanda khi chạy cùng Campaign:

```powershell
rtk docker compose --profile kafka up -d postgres rabbitmq mailhog redpanda
```

Build toàn bộ backend trước để tạo JAR và library `event-contracts`. Từ `backend`, chạy hai terminal:

```powershell
rtk proxy java -jar campaign-service/target/campaign-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,messaging
rtk proxy java -jar notification-service/target/notification-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,worker,events
```

Notification health: http://127.0.0.1:8083/actuator/health.
Tạo/import/start campaign theo [Campaign API](campaign-service.md), rồi xem email ở http://localhost:8025.
Profile `local` chỉ bind loopback; profile `worker` bật listener và handoff publisher.
Flyway tạo schema riêng `notification`, không đọc hoặc cập nhật bảng của Campaign.

Ngoài local, cấp `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USERNAME`, `NOTIFICATION_DB_PASSWORD`,
`SMTP_HOST`, `SMTP_PORT`, `MAIL_FROM` và Spring RabbitMQ connection properties qua môi trường.
SMTP authentication/TLS dùng các Spring Mail properties chuẩn. `.env` của Compose không tự export
biến cho JVM chạy ngoài Docker. SMS hiện luôn là adapter giả lập, kể cả khi đổi profile.

## Contract và trạng thái

Worker nhận JSON `SendNotificationTask` v1 từ exchange `notifyhub.notification.tasks`:

| Queue | Routing key | Provider |
|---|---|---|
| `notifyhub.notification.email` | `notification.send.email` | SMTP, local dùng MailHog |
| `notifyhub.notification.sms` | `notification.send.sms` | Giả lập, reference `sms-simulated:<notificationId>` |

Các trường UUID, version, channel, destination, subject/body và thời gian theo [messaging contract](campaign-messaging.md).
Worker kiểm tra channel khớp queue, version = 1, subject EMAIL không có CR/LF, SMS có E.164 và không có subject.
JSON lỗi, task version không hỗ trợ và task sai channel được giữ nguyên bytes để đưa vào DLQ.
Email là plain text UTF-8; `X-NotifyHub-Notification-Id` và `X-NotifyHub-Correlation-Id` được gửi cùng email.

`notification.deliveries` lưu ownership, SHA-256 của logical payload, trạng thái, số lần gọi provider,
provider reference, error code và timestamps. Các trạng thái đã commit:

- `SENT`: provider đã nhận; SMTP acceptance không bảo đảm thư đã đến hộp thư cuối.
- `RETRY_PENDING`: provider lỗi tạm thời và lịch retry đã được lưu.
- `FAILED`: lỗi vĩnh viễn hoặc đã hết số lần thử, kèm durable DLQ handoff.

`notificationId` là khóa duy nhất. `INSERT ... ON CONFLICT` và row lock tuần tự hóa message trùng,
kể cả giữa các instance. Message đã SENT/FAILED được ack mà không gọi provider lại.
Cùng ID nhưng payload/tenant khác bị quarantine; không ghi đè kết quả gốc.
Khác khoảng trắng/thứ tự field JSON vẫn là cùng logical task.

## Ack, retry và DLQ

Listener dùng **manual ack**, prefetch 16 và 2 consumer cho mỗi queue, có thể override bằng Spring AMQP properties.
Transaction khóa một delivery trong lúc gọi provider; SMTP connect/read/write timeout mặc định 5 giây.

1. Thành công: commit SENT rồi ack inbound message.
2. Lỗi provider: trong cùng transaction lưu attempt/state và retry hoặc DLQ handoff, commit rồi ack inbound.
3. DB/transaction lỗi: rollback và nack/requeue inbound; không ack trước khi handoff bền vững.

Retry dùng PostgreSQL outbox với `available_at`, không phụ thuộc TTL dead-letter của RabbitMQ.
Mặc định tối đa **4 lần gọi provider** (lần đầu + 3 retry), backoff 1/2/4 giây; cấu hình:

```yaml
notification:
  worker:
    max-attempts: 4             # 1..10, tính cả lần đầu
    initial-retry-delay: 1s
    max-retry-delay: 60s        # giới hạn backoff, tối đa 1 giờ
```

Timestamp được chuẩn hóa microsecond theo PostgreSQL. Duplicate đến sớm không vượt qua
`next_attempt_at`; nếu publisher có đồng hồ chạy trước consumer, retry được lưu lại để tránh mất task.
Poller đọc handoff đến hạn bằng
`FOR UPDATE SKIP LOCKED`, republish vào queue gốc và chờ correlated confirm + mandatory return check.
Chỉ đánh dấu published sau broker ack và không có unroutable return.
Broker publish lỗi giữ handoff pending và retry backoff 1/2/4/... tối đa 60 giây;
retry publish không tăng số lần gọi provider.

DLX: `notifyhub.notification.dead-letter`; queue: `notifyhub.notification.email.dlq`
và `notifyhub.notification.sms.dlq`. Message persistent, giữ payload, thêm
`x-notifyhub-attempt` và error code `x-notifyhub-failure`. Không ghi recipient/body vào log lỗi.
FAILED là trạng thái terminal: replay cùng task không reset attempt budget.
Thao tác redrive có kiểm soát chưa có API; cần xử lý nguyên nhân và quy trình operator riêng.

Đây là at-least-once. Crash sau SMTP acceptance nhưng trước DB commit vẫn có thể gửi email trùng,
vì SMTP không hỗ trợ idempotency key. Row locking/deduplication chặn replay sau commit và concurrent calls;
không khẳng định exactly-once đối với provider. Crash sau RabbitMQ confirm có thể tạo DLQ/retry message trùng
nhưng giữ notification ID và attempt budget trong DB.

## Quan sát và kiểm chứng

```sql
SELECT notification_id, tenant_id, campaign_id, status, attempts, provider_reference, last_error
FROM notification.deliveries ORDER BY created_at DESC;
SELECT id, notification_id, exchange_name, available_at, publish_attempts, last_error
FROM notification.delivery_handoffs WHERE published_at IS NULL ORDER BY available_at;
```

Từ `backend`:

```powershell
rtk proxy cmd.exe /d /c mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

`NotificationWorkerIT` dùng PostgreSQL, RabbitMQ và MailHog thật: SMTP body/headers, SMS giả lập,
concurrent duplicates, retry/backoff, SMTP outage, attempt exhaustion, DLQ, unroutable recovery,
task lỗi/identity conflict, transaction rollback với manual nack/redelivery và crash sau confirm.
Không skip integration test khi Docker thiếu.

Worker ghi terminal result vào `notification.result_outbox` cùng transaction delivery state. Profile `events`
phát kết quả Kafka để Campaign tổng hợp completion và Reporting cập nhật read model; xem
[Delivery results và Reporting](delivery-results-reporting.md). Không bật `events` thì outbox giữ kết quả pending.
