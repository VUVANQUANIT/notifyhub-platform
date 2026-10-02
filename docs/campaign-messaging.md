# Campaign messaging pipeline

## Chạy local

```powershell
docker compose --profile kafka up -d postgres rabbitmq redpanda
mvn -f backend/pom.xml -pl campaign-service -am package -DskipTests
java -jar backend/campaign-service/target/campaign-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,messaging
```

Chờ broker sẵn sàng trước khi khởi động service. Nếu một service cũ đang giữ JAR trên Windows,
dừng process đó trước khi build. Chỉ profile `messaging` bật publisher, consumer và dispatcher;
profile `local` đơn thuần vẫn chỉ ghi outbox. Production cần thêm cấu hình JWT, DB và broker
được cấp riêng; không dùng profile `local` trên môi trường dùng chung.

REST flow và CSV mẫu: [Campaign Service](campaign-service.md).
RabbitMQ Management: http://localhost:15672. Các queue chưa có worker nên task sẽ ở trạng thái ready.

## Luồng và transaction

```text
Start Campaign
  -> transaction: RUNNING + outbox_events
  -> OutboxPublisher -> Kafka notifyhub.campaign.events.v1
  -> CampaignStartedListener -> transaction: dispatch_jobs
  -> CampaignDispatcher -> transaction: delivery_tasks + recipient cursor
  -> DeliveryTaskPublisher -> RabbitMQ notifyhub.notification.tasks
                              -> notifyhub.notification.email
                              -> notifyhub.notification.sms
```

- Publisher đọc outbox bằng `FOR UPDATE SKIP LOCKED`, gửi JSON và chờ Kafka ack trước khi ghi
  `published_at`. Kafka key là `campaignId`, producer bật `acks=all` và idempotence.
- Topic mang tất cả event hiện có, như `CampaignCreated`, `CampaignScheduled`, import và
  `CampaignStarted`. Dispatcher chỉ tiếp nhận `CampaignStarted` phiên bản 1.
- Kafka offset chỉ commit sau khi transaction tạo dispatch job thành công. Unique event ID và
  unique `(tenant_id, campaign_id)` ngăn replay tạo thêm job, kể cả event ID mới cho cùng campaign.
- Dispatcher đọc recipient theo UUID cursor, tối đa 100 dòng mỗi transaction. Task và cursor commit
  cùng nhau; khởi động lại tiếp tục từ cursor đã lưu. Khi không còn recipient, job có `completed_at`.
- Mỗi recipient có một task bền vững; `notificationId` được tính ổn định từ tenant/campaign/recipient.
- RabbitMQ sử dụng durable exchange/queue, persistent message, mandatory routing và correlated confirms.
  Chỉ ghi task `published_at` khi broker ack và không trả về message unroutable.
- Không giữ transaction DB mở trong suốt một campaign; mỗi lần publish khóa đúng một row, mỗi batch
  dispatch khóa đúng một job. Publish vẫn giữ row lock trong lúc chờ broker, phù hợp MVP throughput thấp.

Ordering chỉ được Kafka bảo đảm theo thứ tự ghi trong partition. Các publisher đồng thời có thể
phát các loại event khác nhau không theo thứ tự `occurredAt`; dispatcher không phụ thuộc việc thấy
`CampaignCreated` trước `CampaignStarted`, vì campaign đã có trong DB trước khi phát event.

## Contract

Kafka envelope v1 gồm `eventId`, `eventType`, `eventVersion`, `tenantId`, `campaignId`, `occurredAt`,
`correlationId`, `payload`. Timestamp là ISO-8601 UTC. JSON không chứa Java class/type header.

RabbitMQ task v1 gồm:

| Trường | Ý nghĩa |
|---|---|
| `notificationId`, `taskVersion` | Identity ổn định và version = 1 |
| `tenantId`, `campaignId`, `recipientId` | Ownership và nguồn dữ liệu |
| `correlationId` | Giữ correlation từ event start |
| `channel`, `destination` | `EMAIL`/`SMS`, địa chỉ đã chuẩn hóa |
| `subject`, `body` | Snapshot nội dung đã thay biến; subject SMS là null |
| `createdAt` | Thời điểm event start |

Routing key: `notification.send.email` hoặc `notification.send.sms`.
AMQP `messageId` bằng `notificationId`, content type `application/json`, encoding UTF-8.
Biến `{{name}}` lấy từ CSV personalization, thay một lần theo tên, có hỗ trợ khoảng trắng quanh tên.
Biến không có dữ liệu được giữ nguyên literal; đây chưa phải template engine có điều kiện hay escape HTML.

## Retry và recovery

Đây là **at-least-once**, không phải exactly-once end-to-end. Nếu broker đã nhận nhưng DB chưa commit,
publisher sẽ gửi lại cùng event ID hoặc notification ID. Replay Kafka không tạo thêm logical task;
queue RabbitMQ vẫn có thể nhận message trùng. Worker sau này phải deduplicate theo `notificationId`
và dùng idempotency của provider nếu có. Producer confirm không đồng nghĩa email/SMS đã được gửi.

- Kafka/RabbitMQ publish lỗi: giữ record pending, tăng `publish_attempts`, ghi loại lỗi vào `last_error`,
  exponential backoff 1/2/4/... tối đa 60 giây; không tự bỏ message sau N lần.
- Consumer lỗi: retry 2 lần, cách 1 giây, sau đó publish sang `notifyhub.campaign.events.v1.dlt`.
  Chỉ bỏ qua record gốc sau khi publish DLT thành công; cần giám sát và xử lý DLT.
- Sau khi sửa nguyên nhân, replay JSON gốc từ DLT về topic chính, dùng `campaignId` làm key và giữ
  nguyên event ID/correlation ID. Không xóa outbox/job để replay một campaign.
- Consumer kiểm tra campaign thuộc tenant và đang `RUNNING`; replay job đã tiếp nhận vẫn được bỏ qua
  an toàn nếu campaign đã kết thúc. Event chưa có job mà tham chiếu campaign không hợp lệ sẽ vào DLT.
- RabbitMQ mất binding: publisher không đánh dấu published dù broker ack; sửa binding rồi chờ retry.
- Job hoàn tất là đã tạo đủ task; task published là đã vào broker. Campaign vẫn `RUNNING` cho đến khi
  có Delivery Worker và cơ chế tổng hợp kết quả ở bước sau.

SQL quan sát (read-only):

```sql
SELECT event_type, count(*) FROM campaign.outbox_events
WHERE published_at IS NULL GROUP BY event_type;
SELECT event_id, campaign_id, last_recipient_id FROM campaign.dispatch_jobs WHERE completed_at IS NULL;
SELECT notification_id, publish_attempts, next_attempt_at, last_error FROM campaign.delivery_tasks
WHERE published_at IS NULL ORDER BY created_at;
```

`campaign.messaging.batch-size` giới hạn 1..1000; mặc định 100.
`campaign.messaging.polling-enabled=false` dừng các poller, vẫn cho consumer ghi dispatch job.
Topic mặc định 3 partition, replication factor 1 dành cho local; production phải chọn replication
và broker security phù hợp. Spring Kafka/RabbitMQ properties vẫn có thể override qua môi trường.

## Kiểm chứng

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress verify
```

`MessagingPipelineIT` dùng PostgreSQL, Apache Kafka và RabbitMQ Testcontainers độc lập:
email/SMS routing, pagination, publisher đồng thời, replay Kafka, lỗi unroutable, Kafka outage,
rollback sau broker ack và DLT. `MessageRendererTest` kiểm tra substitution một lượt và giá trị literal.

Tham chiếu: [Spring AMQP confirms/returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html),
[CorrelationData contract](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/connection/CorrelationData.html).
