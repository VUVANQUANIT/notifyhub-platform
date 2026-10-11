# Tiến độ NotifyHub — 11/10/2026

## Phạm vi đánh giá

Đánh giá dựa trên workflow và kiểm thử hiện có, không suy ra hoàn thành từ số file hoặc service bootstrap. PR #7/#8/#10/#11/#12 đã merge vào `develop`, gồm Campaign, Worker, delivery results, Reporting, Auth/JWT, Redis controls và email OTP password recovery. Nhánh `quanvv/frontend-auth-gateway` bổ sung Angular login/session/recovery và trang tài khoản/tổ chức; phần frontend cần review và merge trước khi xem là đã tích hợp vào `develop`.

## Hạng mục

| Nhóm | Trạng thái | Bằng chứng / giới hạn |
|---|---|---|
| Nền tảng | Hoàn thành nền MVP | Java 21/Maven, 5 service, Compose, CI backend/container/Trivy; service bootstrap không đồng nghĩa workflow nghiệp vụ |
| Campaign | Hoàn thành luồng chính | Domain/state machine, CSV, API tenant-scoped, PostgreSQL/Flyway, idempotency, lịch chạy, outbox/dispatch; completion khi mọi recipient có kết quả |
| Delivery | Hoàn thành local MVP | RabbitMQ manual ack, SMTP/MailHog, SMS giả lập, durable retry/DLQ, dedup, kết quả Kafka; chưa có provider SMS thật hoặc redrive admin API |
| Reporting | Hoàn thành read model MVP | Kafka projection, trạng thái/counters, API phân trang và lọc, tenant/JWT checks; eventual consistency, chưa có UI |
| Identity/Auth | Hoàn thành core và controls/recovery | Tenant/user/roles, PBKDF2, JWT/JWKS, refresh lifecycle, tenant isolation, Redis quotas/fail-closed, OTP 8 chữ số/TTL/guess cap, encrypted email outbox, atomic password reset/session revoke/notice đã merge; còn MFA, email verification, history cleanup và key rotation |
| Frontend | Auth/session/recovery trên nhánh mới | Angular 22, same-origin Gateway proxy, login/logout, refresh tuần tự, Retry-After, recovery và trang tài khoản/tổ chức; token chỉ trong memory, reload cần login lại. Thiếu campaign/import/reporting UI và member management |
| Observability/audit | Có nền tảng, chưa hoàn thành | Actuator và Compose Prometheus/Grafana; thiếu metrics nghiệp vụ, dashboard, lag monitoring, distributed tracing, audit consumer |
| Deployment/operations | Có CI, chưa hoàn thành | Thiếu k3d/Kubernetes manifests, cấu hình secret/deployment, backup/restore và operator redrive; các PR Dependabot đang mở cần được rà riêng |

Luồng backend chính đã đi được từ đăng nhập/JWT đến tạo campaign, SMTP/SMS giả lập, trạng thái cuối và báo cáo; chống request abuse và recovery qua mailbox đã tích hợp. Frontend trên nhánh mới có workflow Auth, nhưng người dùng chưa thể tạo/gửi/theo dõi campaign trên UI nên chưa hoàn thành MVP giao diện.

Kiểm chứng ngày 05/10/2026: full `clean verify` trên nhánh Auth chạy **151 tests, 0 failures/errors/skipped**, gồm 22 Auth tests và 4 Gateway tests mới. Smoke với bốn JAR độc lập và PostgreSQL riêng đã qua 23 checks: Auth phát token thật, Gateway và hai service kiểm JWKS/JWT, VIEWER không ghi hoặc quản lý user, tenant khác nhận 404, rotation/replay/logout đúng. Kiểm chứng trước đó ở PR #10 đã xác nhận email/SMS thành công; khi SMTP dừng, email FAILED sau 4 attempts còn SMS COMPLETED và Reporting phản ánh đúng cả hai.

Kiểm chứng ngày 06/10/2026 trên nhánh controls/recovery: full `clean verify` chạy **178 tests, 0 failures/errors/skipped**; Auth có 48 tests, Gateway 5 tests. `RecoveryIT` chạy PostgreSQL/Redis/MailHog thật, bao gồm Redis outage, concurrent reset, rollback, expiry, SMTP retry và email notice. Smoke với bốn JAR độc lập đã chạy lại 23 Auth checks và qua recovery: generic 202, normalized cooldown 429/Retry-After, MailHog OTP/notice, password mới, revoke refresh, single use và xóa proof sau 5 lần sai. Compose chính và Compose smoke đều validate thành công.

Kiểm chứng ngày 11/10/2026: backend `verify` chạy lại **178 tests, 0 failures/errors/skipped** với Docker. Frontend có build production, unit tests cho refresh/session và Chromium tests cho login/logout, recovery, Retry-After, reload và mobile layout. Browser smoke qua Gateway/Auth thật cùng PostgreSQL/Redis/MailHog đã xác nhận login, refresh rotation, email OTP, reset mật khẩu, revoke refresh và đăng nhập lại; không lưu trace/video/screenshot có credential. `npm audit` không báo vulnerability. Xem [frontend runbook](../frontend/README.md) và `scripts/smoke-auth-browser.ps1` để chạy lại.

Không gán phần trăm hoàn thành toàn dự án khi chưa có trọng số và acceptance criteria cho UI/vận hành. Các workflow backend cốt lõi đã triển khai; frontend campaign/import/reporting, observability/audit và deployment/operations còn nhiều việc.

## Thứ tự tiếp theo

1. Review/merge frontend Auth/session/recovery sau khi CI thành công.
2. Frontend: danh sách/tạo/start campaign, CSV import, báo cáo và error states; dùng session/interceptor hiện có. Thêm E2E tạo campaign → import → start → kết quả qua Gateway.
3. History retention/cleanup, signing-key rotation, identity audit events, email verification và login MFA; secrets/Redis/SMTP/trusted proxy provisioning cho môi trường chung.
4. Kiểm soát DLQ redrive với authorization và audit.
5. Metrics nghiệp vụ và consumer lag, tracing, Grafana dashboard, audit consumer; k3d deployment và recovery runbook.

## Các giới hạn cần giữ rõ

- SMTP acceptance không bảo đảm thư đến mailbox; crash sau provider acceptance trước DB commit có thể gửi trùng.
- `FAILED` của Campaign nghĩa là mọi recipient đã kết thúc và ít nhất một delivery thất bại; không dừng gửi các recipient còn lại ở failure đầu tiên.
- Kết quả đang thiếu hoặc bị quarantine giữ campaign `RUNNING`; cần quan sát pending outbox/DLT và xử lý nguyên nhân.
- Local actor headers chỉ dùng cho máy phát triển, không dùng thay JWT trên môi trường chung.
- Logout/role change/disable revoke refresh ngay; access token tại Campaign/Reporting còn hiệu lực tới expiry và clock skew. Default access TTL là 5 phút. Production registration mặc định tắt và private key phải được provision ngoài app.
- Việc nâng major Java hoặc dependency không được xem là đã hoàn thành chỉ vì Dependabot mở PR.
