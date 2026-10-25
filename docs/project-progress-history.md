# Lịch sử kiểm thử và backlog MVP (đã lưu trữ)

Trạng thái hiện tại nằm ở [project-progress.md](project-progress.md). Nội dung dưới đây giữ nguyên các mốc kiểm thử/backlog trước khi hoàn tất đợt năm nhóm; các câu “chưa xong/chờ merge” dưới đây chỉ là ghi nhận lịch sử.

## Đợt hoàn thiện vận hành (đang thực hiện)

PR #18 quản lý thành viên đã merge `develop`. Hạng mục 1 trên `quanvv/member-invitations` bổ sung mời/kích hoạt: link 256-bit dùng một lần/24 giờ, SMTP outbox mã hóa/retry giới hạn, resend/revoke và xác minh email khi tự đặt mật khẩu. Auth/Gateway `verify`, production build, 27 unit tests và Chromium activation đã qua. Xem [quy trình mời](member-invitations.md).

Tiếp theo trong đợt này: (2) MFA/rotation/retention, (3) audit/tracing/redrive/cảnh báo ngoài, (4) HTTPS/k3d/secrets/backup, (5) SMTP/Twilio. Các phần sau vẫn chưa được đánh dấu hoàn thành. Tài khoản và credentials provider/domain thực tế được provision ngoài Git; không suy ra đã triển khai production từ manifest.

Hạng mục 2 trên `quanvv/auth-hardening`: MFA TOTP và 8 recovery codes dùng một lần, UI enrollment/login/disable, revoke refresh, password recovery không bypass MFA; overlap public JWKS và runbook rotation; hourly bounded retention. Production build, 27 frontend unit tests và 28 Chromium tests qua. Auth verify bổ sung RFC vectors, rotation, MFA lifecycle và retention. Xem [hardening](auth-hardening.md).

Hạng mục 3 trên `quanvv/operations-audit`: identity audit transactional outbox → Kafka → Reporting (dedup/DLT), ADMIN tenant-scoped audit API, metrics backlog; ADMIN audited/idempotent redrive từ durable dead-letter handoff với delivery ID mới và lịch sử riêng; OTLP tracing/Tempo và receiver Alertmanager webhook đọc secret file. Recovery không ghi đè kết quả campaign terminal. Backend integration đang chạy; receiver thật cần provision URL/token. Xem [operations](operations-audit.md).

## Phạm vi đánh giá

PR #19/#20/#21 đã qua toàn bộ CI, gồm CodeQL và container Trivy. Hạng mục 4 trên `quanvv/k3d-deployment`: cluster riêng, 12 pod Ready, 45 resource valid, TLS cùng origin/CSP/Trusted Types, secret/DB roles riêng, initial ADMIN qua operator channel, backup AES-256-GCM và restore namespace riêng. Live Chromium qua invitation/activation/MFA recovery/audit và email campaign completion; không có CSP violation. Tempo đã nhận trace thật. Đã sửa service-link env collision và RabbitMQ cấu hình Worker ngoài local. Provider thật vẫn cần credentials; k3d là môi trường validation một node. Xem [deployment](deployment.md).

Đánh giá dựa trên workflow và kiểm thử hiện có, không suy ra hoàn thành từ số file hoặc service bootstrap. PR #7/#8/#10/#11/#12/#13/#15/#16 đã merge vào `develop`; PR #17 đã tích hợp `develop` vào `main`. Nền hiện có: backend Campaign/Worker/Reporting, Auth/JWT/Redis/OTP, Monitoring và frontend campaign/reporting. Nhánh `quanvv/member-management` bổ sung UI quản lý thành viên ADMIN, cần review/merge để tích hợp phần mới.

## Hạng mục

| Nhóm | Trạng thái | Bằng chứng / giới hạn |
|---|---|---|
| Nền tảng | Hoàn thành nền MVP | Java 21/Maven, 5 service, Compose, CI backend/container/Trivy; service bootstrap không đồng nghĩa workflow nghiệp vụ |
| Campaign | Hoàn thành luồng chính | Domain/state machine, CSV, API tenant-scoped, PostgreSQL/Flyway, idempotency, lịch chạy, outbox/dispatch; completion khi mọi recipient có kết quả |
| Delivery | Hoàn thành local MVP | RabbitMQ manual ack, SMTP/MailHog, SMS giả lập, durable retry/DLQ, dedup, kết quả Kafka; chưa có provider SMS thật hoặc redrive admin API |
| Reporting | Hoàn thành read model và UI MVP | Kafka projection, trạng thái/counters, API và UI phân trang/lọc, tenant/JWT checks; eventual consistency, làm mới thủ công |
| Identity/Auth | Hoàn thành core và controls/recovery | Tenant/user/roles, PBKDF2, JWT/JWKS, refresh lifecycle và tenant isolation đã merge. Đã tích hợp: Redis quotas/fail-closed, OTP 8 chữ số/TTL/guess cap, encrypted email outbox, atomic password reset/session revoke/notice; còn MFA, email verification, history cleanup và key rotation |
| Frontend | Hoàn thành luồng MVP; member UI trên nhánh mới | Angular Auth/recovery và campaign/import/reporting. Nhánh mới: danh sách thành viên/phân trang, create, role/enabled confirmation, last-admin errors, stale-role handling và self-demotion logout. Còn invitation, first-login password change, login MFA và session persistence |
| Observability/audit | Monitoring đã tích hợp; còn tracing/audit | Authenticated scrape, metrics nghiệp vụ/backlog, RabbitMQ queues/DLQ, Kafka group lag, Grafana và Alertmanager local; còn tracing, audit consumer và receiver bên ngoài |
| Deployment/operations | Có CI, chưa hoàn thành | Thiếu k3d/Kubernetes manifests, cấu hình secret/deployment, backup/restore và operator redrive; các PR Dependabot đang mở cần được rà riêng |

Luồng MVP đã chạy qua trình duyệt từ login đến tạo campaign, import CSV, SMTP/MailHog hoặc SMS giả lập, completion và báo cáo. Nhánh mới hoàn thiện thao tác quản lý thành viên bằng API Auth sẵn có. Đây là MVP local; provider production, quy trình mời/cấp tài khoản, audit/tracing và deployment/recovery vẫn cần hoàn thiện.

Kiểm chứng ngày 05/10/2026: full `clean verify` trên nhánh Auth chạy **151 tests, 0 failures/errors/skipped**, gồm 22 Auth tests và 4 Gateway tests mới. Smoke với bốn JAR độc lập và PostgreSQL riêng đã qua 23 checks: Auth phát token thật, Gateway và hai service kiểm JWKS/JWT, VIEWER không ghi hoặc quản lý user, tenant khác nhận 404, rotation/replay/logout đúng. Kiểm chứng trước đó ở PR #10 đã xác nhận email/SMS thành công; khi SMTP dừng, email FAILED sau 4 attempts còn SMS COMPLETED và Reporting phản ánh đúng cả hai.

Kiểm chứng ngày 06/10/2026 trên nhánh controls/recovery: full `clean verify` chạy **178 tests, 0 failures/errors/skipped**; Auth có 48 tests, Gateway 5 tests. `RecoveryIT` chạy PostgreSQL/Redis/MailHog thật, bao gồm Redis outage, concurrent reset, rollback, expiry, SMTP retry và email notice. Smoke với bốn JAR độc lập đã chạy lại 23 Auth checks và qua recovery: generic 202, normalized cooldown 429/Retry-After, MailHog OTP/notice, password mới, revoke refresh, single use và xóa proof sau 5 lần sai. Compose chính và Compose smoke đều validate thành công.

Không gán phần trăm hoàn thành toàn dự án khi chưa có trọng số và acceptance criteria cho vận hành. Các workflow MVP backend/frontend đã triển khai; production hardening, audit/tracing và deployment/operations còn nhiều việc.

Kiểm chứng ngày 13/10/2026 trên nhánh `quanvv/monitoring`: **190 tests, 0 failures/errors/skipped**. Promtool kiểm tra 11 alert rules và toàn bộ PromQL của dashboard 26 panel. Smoke local xác nhận 5 endpoint scrape có authentication, 4 database snapshots healthy, RabbitMQ/Kafka exporter được scrape; Kafka backlog thử nghiệm đo đúng 2 records, DLQ alert thực sự firing và có trong Alertmanager. Queue/topic/group thử nghiệm được dọn sau kiểm chứng.

Kiểm chứng đợt 14–15/10/2026: PR #15 bị Trivy chặn ở Campaign/Notification/Reporting vì `at.yawk.lz4:lz4-java 1.10.1` (CVE-2026-106451 HIGH). Đã pin bản vá `1.11.4`, full backend **190 tests, 0 failures/errors/skipped**, CI #15 10/10 xanh. PR #13 cập nhật Monitoring, CI 9/9 xanh rồi merge. Frontend campaign/reporting có build production (~300 kB initial), **21 unit tests và 14 Chromium tests** qua; browser smoke với 5 JAR/PostgreSQL/Redis/Kafka/RabbitMQ/MailHog thật xác nhận email/SMS completion, CSV valid/duplicate/invalid rows, email MailHog, counters, cross-tenant 404 và VIEWER backend 403. Smoke dùng project/ports riêng và dọn JVM/container/network, giữ nguyên stack đang chạy của người dùng. Xem [frontend runbook](../frontend/README.md).

Kiểm chứng ngày 17/10/2026 trên `quanvv/member-management`: production build thành công (~301 kB initial), **27 unit tests và 22 Chromium tests** qua. Live smoke riêng với 2 JAR Auth/Gateway và PostgreSQL/Redis/MailHog xác nhận last-admin role/disable 409, create/role/enable lifecycle, refresh session revocation, tenant 404, VIEWER 403 và self-demotion logout/login. Không thay đổi API/schema backend. Stack smoke/JVM được dọn sau test; log/key local được ignore. Phần mới chờ CI và review/merge.

## Thứ tự tiếp theo

1. Review/merge member management sau khi CI xanh; tiếp theo là email invitation, email verification và đổi mật khẩu lần đầu để hoàn thiện quy trình cấp tài khoản.
2. History retention/cleanup, signing-key rotation, identity audit events, email verification và login MFA; secrets/Redis/SMTP/trusted proxy provisioning cho môi trường chung.
3. Cấu hình deployment frontend same-origin/HTTPS, CSP và provider email/SMS production.
4. Kiểm soát DLQ redrive với authorization và audit.
5. Distributed tracing, audit consumer; k3d deployment và recovery runbook. Monitoring có dashboard và cảnh báo local; cần cấu hình receiver khi muốn gửi thông báo ra ngoài.

## Các giới hạn cần giữ rõ

- SMTP acceptance không bảo đảm thư đến mailbox; crash sau provider acceptance trước DB commit có thể gửi trùng.
- `FAILED` của Campaign nghĩa là mọi recipient đã kết thúc và ít nhất một delivery thất bại; không dừng gửi các recipient còn lại ở failure đầu tiên.
- Kết quả đang thiếu hoặc bị quarantine giữ campaign `RUNNING`; cần quan sát pending outbox/DLT và xử lý nguyên nhân.
- Local actor headers chỉ dùng cho máy phát triển, không dùng thay JWT trên môi trường chung.
- Logout/role change/disable revoke refresh ngay; access token tại Campaign/Reporting còn hiệu lực tới expiry và clock skew. Default access TTL là 5 phút. Production registration mặc định tắt và private key phải được provision ngoài app.
- Việc nâng major Java hoặc dependency không được xem là đã hoàn thành chỉ vì Dependabot mở PR.
