# Tiến độ NotifyHub — 17/10/2026

MVP đã có đủ Campaign → RabbitMQ Worker → SMTP/SMS → Kafka results → Reporting, Auth/JWT/refresh/OTP và giao diện quản lý thành viên. PR #18 đã merge `develop`. Đợt hiện tại hoàn thiện năm nhóm còn lại bằng năm nhánh `quanvv/`, mỗi PR chỉ chứa một nhóm và dựa trên nhánh trước.

| Hạng mục | PR / nhánh | Trạng thái và bằng chứng |
|---|---|---|
| 1. Mời/kích hoạt thành viên | #19 — `quanvv/member-invitations` | Code/kiểm thử xong, CI xanh. Link 256-bit/24 giờ/dùng một lần; resend/revoke, encrypted SMTP outbox, email verification khi tự đặt mật khẩu; tenant/live ADMIN checks |
| 2. Bảo mật tài khoản | #20 — `quanvv/auth-hardening` | Code/kiểm thử xong, CI xanh. TOTP + 8 recovery codes dùng một lần, revoke refresh, reset password giữ MFA; public JWKS overlap/rotation; bounded retention |
| 3. Audit/vận hành | #21 — `quanvv/operations-audit` | Code/kiểm thử xong, CI xanh. Transactional audit outbox/Kafka consumer/dedup/DLT, ADMIN audit API; audited/idempotent one-off redrive; OTLP/Tempo, webhook receiver đọc secret file |
| 4. Triển khai/khôi phục | #22 — `quanvv/k3d-deployment` | Code/kiểm thử xong, CI xanh. K3d riêng: 12 pod Ready, 45 resource valid, HTTPS/CSP/Trusted Types, secret/DB roles riêng, bootstrap không mở registration, encrypted backup + isolated restore |
| 5. Provider thực tế | #23 — `quanvv/delivery-providers` | SMTP STARTTLS/auth/hostname guard, Twilio API-key adapter, safe errors, bounded response/timeouts, Retry-After trong trần retry; tests local protocol/worker và Spring provider selection đã qua. CI đang chạy; activation thật cần credentials |

## Bằng chứng kiểm thử

- Backend full verify sau audit/redrive qua PostgreSQL/Redis/MailHog/Kafka/RabbitMQ, gồm HTTP role/tenant checks, concurrent activation, MFA recovery, audit rollback/dedup và redrive giữ nguyên campaign terminal result. PR #19–#22 đều qua CodeQL, backend/frontend, dependency review và container Trivy; #22 thêm frontend container scan.
- Frontend: production build, 27 unit tests và 28 Chromium tests. Live Chromium qua HTTPS/k3d xác nhận invitation → activation → MFA/recovery → audit → email campaign `COMPLETED`, không có CSP violation; không lưu token vào browser storage.
- Backup AES-256-GCM được kiểm tra tamper/overwrite protection. Snapshot thật đã authenticate và restore vào namespace riêng, truy vấn được **3 users, 2 campaigns, 2 deliveries, 2 reports**; namespace gốc không bị thay thế. Namespace restore và plaintext scratch được dọn sau test.
- Tempo đã nhận trace thật từ các service. Webhook config qua amtool; endpoint nhận cảnh báo bên ngoài chưa được cấp cấu hình thật.
- Twilio tests chạy với HTTP server cục bộ: UTF-8/API-key, acceptance SID, auth/rate/server failures, oversized/malformed responses. Worker integration qua 429 → capped durable retry → accepted → dedup. Không gửi tới số điện thoại thật.

## Cách tích hợp

Review/merge lần lượt **#19 → #20 → #21 → #22 → #23**. Các PR đang xếp chồng để nhóm sau dùng nhóm trước; chưa tự merge các thay đổi của đợt này vào `develop`/`main`. Khi merge từng PR, kiểm tra base của PR kế tiếp đã về `develop` trước khi tiếp tục.

## Phần còn cần cấu hình và nghiệm thu bên ngoài

1. Cấp SMTP host/account, sender/domain và Twilio account/API key/sender; đặt trong secret ngoài Git, kích hoạt theo [provider runbook](delivery-providers.md). Dùng người nhận được operator chọn để kiểm tra gửi thật.
2. Cấp tên miền/certificate CA thật và receiver webhook, cấu hình topology proxy/quotas, HA/external storage/brokers nếu dùng chung/production. Bản k3d hiện tại là validation một node, TLS self-signed, không tuyên bố HA hoặc SLA.
3. Đưa encrypted backups ra storage độc lập, giữ backup key tách biệt và chạy restore drills theo lịch vận hành. Bản backup cùng ổ đĩa chưa bảo vệ khỏi mất máy.

Không gán tỷ lệ phần trăm production readiness khi chưa có acceptance criteria cho môi trường và các tài khoản dịch vụ bên ngoài.

## Giới hạn giữ rõ

- SMTP/Twilio acceptance không bảo đảm đến mailbox/handset. Delivery receipts/carrier callbacks chưa được triển khai; crash sau acceptance trước DB commit có thể gửi trùng.
- Recovery delivery có ID và lịch sử riêng, không ghi đè kết quả campaign đã terminal. Một lần redrive cho mỗi original; poison/identity conflict không được tự phát lại, broker DLQ giữ làm evidence.
- Audit là success-event stream trong database/Kafka, không phải kho lưu trữ bất biến chống sửa; failed login chưa nằm trong stream này.
- MFA opt-in. Mất authenticator và mọi recovery code cần quy trình xác minh ngoài hệ thống, không có password-only bypass. Tài khoản cũ/cấp trực tiếp chưa tự động được đánh dấu email verified.
- Logout/role/disable/MFA changes revoke refresh; downstream access JWT còn hiệu lực đến expiry/skew (mặc định 5 phút). Session frontend vẫn nằm trong memory và cần login lại khi reload.
- Tracing quan sát HTTP/message hops; durable outbox scheduling tạo trace mới, correlation ID liên kết các bước. Không tuyên bố một trace liên tục qua toàn bộ committed outboxes.
- PR Dependabot major đang mở cần review riêng; không nằm trong đợt năm hạng mục này.

Chi tiết: [invitation](member-invitations.md), [Auth hardening](auth-hardening.md), [operations](operations-audit.md), [deployment/recovery](deployment.md), [providers](delivery-providers.md). Các kết quả và backlog MVP cũ được giữ ở [lịch sử tiến độ](project-progress-history.md); không dùng backlog lịch sử làm trạng thái hiện tại.
