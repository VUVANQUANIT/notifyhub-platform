# Campaign Service: persistence và REST API

## Chạy local

PostgreSQL của Compose có volume riêng; Flyway tạo schema `campaign` khi service khởi động.
Hibernate dùng `ddl-auto=validate`, không tự thay đổi bảng.

Từ thư mục repository:

```powershell
docker compose up -d --wait postgres
cd backend
.\mvnw.cmd -pl campaign-service spring-boot:run "-Dspring-boot.run.profiles=local"
```

Service: `http://127.0.0.1:8082`. Health: `/actuator/health` (bao gồm kiểm tra PostgreSQL).
Profile `local` bind loopback và nhận `X-Tenant-Id`, `X-User-Id` dạng UUID cho mọi API.
Đây là actor giả lập phục vụ phát triển, không xác thực quyền sở hữu tenant.

Ngoài local, Spring Security yêu cầu JWT được xác minh. Profile `prod` cần:

- `CAMPAIGN_DB_URL`: JDBC URL PostgreSQL.
- `CAMPAIGN_DB_USERNAME`, `CAMPAIGN_DB_PASSWORD`: credential được cấp qua môi trường.
- `JWT_ISSUER_URI`: issuer thật hỗ trợ discovery/JWK.
- `JWT_AUDIENCE`: mặc định `notifyhub`.
- JWT: `sub` là user UUID, `tenant_id` là tenant UUID, `scope` chứa `campaigns:read` cho GET hoặc `campaigns:write` cho POST.

Header tenant/user không được dùng ở production. Không bật đồng thời `local` và `prod`.
Auth Service/issuer chưa được triển khai trong repository này; cấu hình prod cần issuer bên ngoài.
Với môi trường có phân quyền DB riêng, chạy migration bằng tài khoản migration trước và cấp tài khoản runtime
quyền SELECT/INSERT/UPDATE trên schema; không dùng tài khoản superuser local cho production.

## HTTP contract

OpenAPI: [campaign-openapi.json](api/campaign-openapi.json).

| Method | Endpoint | Kết quả |
|---|---|---|
| POST | /api/campaigns | Tạo DRAFT; bắt buộc Idempotency-Key |
| GET | /api/campaigns?status=DRAFT&page=0&size=20 | Lọc trạng thái, phân trang theo tenant |
| GET | /api/campaigns/{id} | Chi tiết campaign |
| POST | /api/campaigns/{id}/imports | Multipart field `file`, CSV UTF-8 |
| GET | /api/campaigns/{id}/imports/{importId} | Summary và lỗi theo dòng |
| GET | /api/campaigns/{id}/recipients?page=0&size=20 | Danh sách recipient |
| POST | /api/campaigns/{id}/start | Start hoặc schedule; bắt buộc Idempotency-Key |

Campaign/recipient/import của tenant khác trả 404. Size từ 1 đến 100, page từ 0.
Lỗi request trả 400; vi phạm state/idempotency/concurrent constraint trả 409.
Upload quá giới hạn multipart trả 413. API dùng ProblemDetail cho lỗi.

Ví dụ PowerShell khi chạy local:

```powershell
$headers = @{
  "X-Tenant-Id" = "20000000-0000-0000-0000-000000000001"
  "X-User-Id" = "30000000-0000-0000-0000-000000000001"
  "Idempotency-Key" = [guid]::NewGuid().ToString()
}
$body = @{
  name = "Welcome"
  channel = "EMAIL"
  subject = "Hello"
  body = "Hello {{name}}"
} | ConvertTo-Json
$campaign = Invoke-RestMethod http://127.0.0.1:8082/api/campaigns -Method Post -Headers $headers -ContentType application/json -Body $body

# PowerShell 7 supports multipart -Form.
Invoke-RestMethod "http://127.0.0.1:8082/api/campaigns/$($campaign.id)/imports" -Method Post -Headers $headers -Form @{
  file = Get-Item ../docs/examples/recipients.csv
}
$headers["Idempotency-Key"] = [guid]::NewGuid().ToString()
Invoke-RestMethod "http://127.0.0.1:8082/api/campaigns/$($campaign.id)/start" -Method Post -Headers $headers
```

## Transaction và import

Import đồng bộ, tối đa 2 MiB / 10.000 data records mỗi file; số dòng lỗi là số thứ tự record CSV
(header = 1), không phải số dòng vật lý khi trường quoted chứa newline.
Email dùng cột `email`, SMS dùng `phoneNumber` với tiền tố quốc tế +; không tự đoán country code.
Các cột khác giữ làm personalization. Email được trim/lowercase; số điện thoại bỏ ký tự phân cách.
Dòng sai không dừng các dòng còn lại. Địa chỉ trùng giữ dữ liệu hợp lệ đầu tiên; reimport không ghi đè personalization.

CSV hỏng cấu trúc/header/encoding hoặc vượt số dòng tạo batch `REJECTED` có lý do, HTTP 201
vì kết quả import đã được lưu. Không lưu recipient một phần của file bị reject.
Batch toàn dòng không hợp lệ vẫn `COMPLETED` với validRows=0; Campaign không thể start nếu chưa có recipient hợp lệ.
Chỉ các destination có trong upload được truy vấn để deduplicate; không tải toàn bộ recipient của Campaign.

Import và start đều khóa row Campaign trong transaction. Import đồng thời được tuần tự hóa;
start đợi import đang giữ khóa hoàn tất. Partial unique index bảo vệ một batch PROCESSING,
unique constraint bảo vệ destination và foreign key kép bảo vệ tenant/campaign ownership.
`@Version` phát hiện stale update. Timestamp được chuẩn hóa microsecond theo PostgreSQL.

Create idempotent theo (tenant, key): cùng payload và actor trả cùng campaign; payload/actor khác trả 409.
Advisory transaction lock bảo vệ request create đồng thời giữa các instance.
Start retry cùng key trả trạng thái hiện tại; key khác sau start trả 409.
Import lại được deduplicate, nhưng mỗi upload vẫn có một import record riêng.

Scheduler quét tối đa 100 campaign đến hạn mỗi lượt (mặc định 5 giây); khóa và kiểm tra lại trạng thái
trước khi activate nên nhiều instance không tạo event start trùng.
Có thể tắt bằng `campaign.scheduling.enabled=false`.

## Outbox và phạm vi hiện tại

CampaignCreated, CampaignScheduled, CampaignStarted, RecipientImportCompleted/Rejected được ghi vào
`campaign.outbox_events` trong cùng transaction với business data. Envelope có event ID, type,
version, tenant, campaign, occurredAt, correlationId và JSON payload. Correlation ID hiện do service sinh.
Profile `messaging` bật publisher Kafka, dispatcher và publisher RabbitMQ; xem [Campaign messaging](campaign-messaging.md).
RUNNING biểu thị đã ghi ý định bắt đầu và có thể đã đưa task vào queue.
[Notification Worker](notification-worker.md) gửi email/SMS và ghi delivery-result outbox cùng transaction.
Campaign tổng hợp kết quả Kafka và chuyển COMPLETED/FAILED khi mọi recipient có kết quả terminal;
xem [Delivery results và Reporting](delivery-results-reporting.md).

## Kiểm chứng

```powershell
cd backend
.\mvnw.cmd -B -ntp clean verify
```

Surefire chạy unit test; Failsafe chạy `*IT` với PostgreSQL Testcontainers độc lập.
Docker phải hoạt động; không tự skip integration test khi thiếu Docker.
Test bao phủ REST flow, reload DB, tenant isolation, JSON errors, CSV reject,
concurrent create/import/start, uniqueness/FK/optimistic locking, rollback outbox, scheduled activation và JWT.
GitHub Actions `clean verify` chạy các test này trên PR và push main/develop.
