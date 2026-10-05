# Tiến độ NotifyHub — 05/10/2026

## Phạm vi đánh giá

Đánh giá dựa trên workflow và kiểm thử hiện có, không suy ra hoàn thành từ số file hoặc service bootstrap. Nhánh `quanvv/delivery-results-reporting` bổ sung chặng kết quả gửi → kết thúc campaign → báo cáo; `develop` đã có Campaign API và Notification Worker từ PR #7/#8. Các thay đổi trên nhánh mới cần được review và merge trước khi xem là đã tích hợp vào `develop`.

## Hạng mục

| Nhóm | Trạng thái | Bằng chứng / giới hạn |
|---|---|---|
| Nền tảng | Hoàn thành nền MVP | Java 21/Maven, 5 service, Compose, CI backend/container/Trivy; service bootstrap không đồng nghĩa workflow nghiệp vụ |
| Campaign | Hoàn thành luồng chính | Domain/state machine, CSV, API tenant-scoped, PostgreSQL/Flyway, idempotency, lịch chạy, outbox/dispatch; completion theo mọi recipient có kết quả trên nhánh mới |
| Delivery | Hoàn thành local MVP | RabbitMQ manual ack, SMTP/MailHog, SMS giả lập, durable retry/DLQ, dedup; kết quả Kafka trên nhánh mới; chưa có provider SMS thật hoặc redrive admin API |
| Reporting | Hoàn thành read model MVP trên nhánh mới | Kafka projection, trạng thái/counters, API phân trang và lọc, tenant/JWT checks; eventual consistency, chưa có UI |
| Identity/Auth | Chưa hoàn thành | Auth chỉ bootstrap; chưa có tenant/user/roles, password/login, JWT issuer, refresh rotation, OTP/rate limiting; Campaign/Reporting đã kiểm tra JWT từ issuer ngoài |
| Frontend | Chưa triển khai | Chỉ có README; thiếu Angular app, đăng nhập, campaign/import UI và dashboard |
| Observability/audit | Có nền tảng, chưa hoàn thành | Actuator và Compose Prometheus/Grafana; thiếu metrics nghiệp vụ, dashboard, lag monitoring, distributed tracing, audit consumer |
| Deployment/operations | Có CI, chưa hoàn thành | Thiếu k3d/Kubernetes manifests, cấu hình secret/deployment, backup/restore và operator redrive; các PR Dependabot đang mở cần được rà riêng |

Luồng backend chính đã đi được từ tạo campaign đến SMTP/SMS giả lập, trạng thái cuối cùng và truy vấn báo cáo. Đây chưa phải MVP dùng được bởi người dùng cuối: Auth và frontend vẫn là hai khoảng trống lớn.

Kiểm chứng ngày 05/10/2026: full `clean verify` chạy 124 tests, 0 failures/errors/skipped; thêm regression worker clock skew và chạy lại toàn bộ Campaign suite đưa bộ kiểm thử lên 125 tests. Smoke qua ba JAR độc lập và PostgreSQL/Kafka/RabbitMQ/MailHog riêng đã xác nhận email/SMS thành công, tenant khác nhận 404; khi SMTP dừng, email kết thúc FAILED sau 4 attempts và SMS vẫn COMPLETED, Reporting phản ánh đúng cả hai. Docker build Reporting và cả hai Compose config đã được kiểm tra.

Không gán phần trăm hoàn thành toàn dự án khi chưa có trọng số và acceptance criteria cho UI/Auth/vận hành. Theo 8 nhóm ở bảng, 4 nhóm có workflow MVP chính đã triển khai; 4 nhóm còn công việc. Số nhóm không đại diện cho tỷ lệ công sức hoặc thời gian còn lại.

## Thứ tự tiếp theo

1. Review/merge delivery results và Reporting sau khi CI thành công.
2. Identity/Auth: tenant/user/roles, password hash/login, JWT signing/JWKS, refresh-token rotation/revoke, integration tests chống truy cập khác tenant. Kết nối issuer với Campaign/Reporting và gateway.
3. Frontend: đăng nhập, danh sách/tạo/start campaign, CSV import, báo cáo campaign và error states. Thêm E2E qua gateway với Auth thật.
4. Redis OTP/rate limiting; kiểm soát DLQ redrive với authorization và audit.
5. Metrics nghiệp vụ và consumer lag, tracing, Grafana dashboard, audit consumer; k3d deployment và recovery runbook.

## Các giới hạn cần giữ rõ

- SMTP acceptance không bảo đảm thư đến mailbox; crash sau provider acceptance trước DB commit có thể gửi trùng.
- `FAILED` của Campaign nghĩa là mọi recipient đã kết thúc và ít nhất một delivery thất bại; không dừng gửi các recipient còn lại ở failure đầu tiên.
- Kết quả đang thiếu hoặc bị quarantine giữ campaign `RUNNING`; cần quan sát pending outbox/DLT và xử lý nguyên nhân.
- Local actor headers chỉ dùng cho máy phát triển, không dùng thay JWT trên môi trường chung.
- Việc nâng major Java hoặc dependency không được xem là đã hoàn thành chỉ vì Dependabot mở PR.
