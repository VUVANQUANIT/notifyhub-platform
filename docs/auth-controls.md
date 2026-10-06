# Redis rate limiting và OTP recovery

Nhánh `quanvv/auth-rate-limit-otp` bổ sung rate limiting phân tán cho Auth và OTP email dùng để khôi phục mật khẩu. Đây là recovery bằng email; login vẫn dùng password/JWT. Login MFA, xác minh email khi đăng ký và SMS OTP là phạm vi khác chưa triển khai.

## Chạy local

```powershell
docker compose up -d postgres redis mailhog
```

Build backend và chạy Auth/Gateway theo [Auth runbook](auth-service.md). Auth `local` mặc định kết nối Redis `127.0.0.1:6379`, SMTP `127.0.0.1:1025`. MailHog UI: `http://127.0.0.1:8025`.

Auth local tạo `.env.auth-control-secret` gồm 32 bytes ngẫu nhiên encode Base64 và dùng lại sau restart. Git và Docker context ignore file này. Secret độc lập với RSA signing key; cùng các replica Auth phải dùng cùng secret. Ngoài local, `AUTH_CONTROL_SECRET_PATH` phải trỏ tới file được provision, mount read-only và giới hạn quyền file/ACL. Không thay secret khi còn OTP/outbox đang pending: digest cũ sẽ không xác minh được và payload cũ không giải mã được.

Sau khi Auth/Gateway local đã ready, kiểm tra luồng recovery (script tạo tenant/user mới và không in OTP/token):

```powershell
.\scripts\smoke-auth-recovery.ps1
```

Có thể đổi endpoint bằng `-GatewayUrl` và `-MailHogUrl`. Integration tests dùng PostgreSQL/Redis/MailHog Testcontainers riêng, không flush Redis Compose của người dùng.

Biến kết nối: `AUTH_REDIS_HOST`, `AUTH_REDIS_PORT`, `AUTH_REDIS_PASSWORD`; `AUTH_SMTP_HOST`, `AUTH_SMTP_PORT`, `AUTH_SMTP_USERNAME`, `AUTH_SMTP_PASSWORD`, `AUTH_SMTP_AUTH`, `AUTH_SMTP_STARTTLS`, `AUTH_MAIL_FROM`. Không dùng SMTP/MailHog local như provider production. Bật `AUTH_SMTP_AUTH=true` và `AUTH_SMTP_STARTTLS=true` khi provider yêu cầu; STARTTLS khi bật là bắt buộc, không downgrade sang plaintext.

## Giới hạn mặc định

| Phạm vi | Quota | Window |
|---|---:|---|
| POST/PUT/PATCH/DELETE dưới `/api/auth/`, theo IP | 120 | 1 phút |
| Login, theo tenant slug + email chuẩn hóa | 10 | 15 phút |
| Refresh, theo token | 30 | 1 phút |
| Reset, theo challenge ID | 10 | 1 phút |
| Gửi OTP, theo tenant slug + email | 3 | 15 phút |
| Gửi OTP, theo IP | 10 | 15 phút |
| Cooldown resend, theo tenant slug + email | 1 | 1 phút |
| OTP nhập sai | 5 | Suốt lifetime của challenge |

Các rate policy nằm dưới `auth.control.requests/login/refresh/reset/otp.requests/otp.ip-requests` với `limit` và `window`. Counter dùng fixed window bắt đầu ở request đầu tiên, có TTL; request bị từ chối không kéo dài window. Quota tính request, kể cả login đúng hoặc resend bị chặn bởi cooldown; các policy độc lập được kiểm tra tuần tự. Client phải tôn trọng `Retry-After`, không gửi vòng retry liên tục.

Lua gộp thao tác counter/expiry, vì vậy nhiều instance không thể vượt quota bằng race. Redis key dùng HMAC-SHA256 thay email/IP/raw refresh token; OTP hash cũng được HMAC với secret và challenge ID. Tham khảo [Redis INCR và Lua](https://redis.io/docs/latest/commands/incr/) và [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).

API trả **429** với `Retry-After` khi vượt quota; Redis không sẵn sàng trả **503** và không thực hiện Auth write. GET JWKS vẫn hoạt động khi Redis lỗi. Rate limiting được kiểm ở Auth nên gọi trực tiếp service vẫn chịu giới hạn; những REST service khác chưa có edge rate limiting. Redis restart/loss có thể làm mất quota counters và OTP proof; DB receipt vẫn ngăn OTP đã dùng bị replay. Production cần Redis riêng cho controls, ACL/network isolation và persistence/availability phù hợp; không dùng policy eviction dễ xóa controls tùy ý.

## IP và proxy

`server.forward-headers-strategy=none` giữ socket peer làm nguồn tin cậy. Mặc định Auth bỏ qua `X-Forwarded-For`; client không thể tự đổi IP để vượt quota. Khi qua Gateway, quota IP mặc định dùng địa chỉ Gateway, còn quota account vẫn độc lập.

Muốn quota theo IP người dùng sau proxy, proxy phải thêm địa chỉ socket client vào `X-Forwarded-For`, và cấu hình `AUTH_TRUSTED_PROXY_ADDRESSES` bằng danh sách IP literal chính xác của những proxy được kiểm soát. Auth duyệt chain từ phải sang trái, chỉ qua các peer được trust, dừng ở peer đầu tiên không trust; header malformed/hostname quay về socket peer, không DNS lookup hostname. Chỉ cấu hình trust khi Auth nằm trong network riêng và không cho client trực tiếp đi từ địa chỉ proxy. Không trust một CIDR rộng hoặc nguồn header do browser tự đặt.

## API recovery

`POST /api/auth/password/forgot`, JSON:

```json
{"tenantSlug":"demo-team","email":"admin@example.com"}
```

Trả **202** với `challengeId` và thông báo chung giống nhau cho tài khoản tồn tại/không tồn tại/disabled. Response không chứa OTP, email destination hoặc token đăng nhập. Server tạo decoy Redis proof cho account không đủ điều kiện và không gửi mail. SMTP gửi bất đồng bộ qua outbox; thời gian API không phụ thuộc SMTP, nhưng không cam kết constant-time tuyệt đối giữa các nhánh DB.

Account đủ điều kiện nhận email chứa **8 chữ số ngẫu nhiên**, challenge ID và thời điểm hết hạn. OTP hết hạn tối đa sau 5 phút; nhập sai 5 lần xóa Redis proof. Chỉ challenge mới nhất cho user còn hiệu lực. Resend không đổi password hoặc khóa account login.

`POST /api/auth/password/reset`, JSON:

```json
{"challengeId":"UUID từ response hoặc email","code":"01234567","password":"your new long unique passphrase"}
```

Trả **204** khi thành công; password policy giống register. Password mới, consumed receipt, version invalidation, revoke mọi refresh session của user và email thông báo đổi mật khẩu được ghi trong cùng PostgreSQL transaction. Không tự đăng nhập sau reset; client gọi login bình thường. Sai/expired/replayed/disabled/ineligible challenge trả cùng 401. Access JWT cũ vẫn có thể dùng tại downstream tới expiry và clock skew như [Auth runbook](auth-service.md) mô tả.

Redis chỉ xác minh proof và đếm lần sai; PostgreSQL receipt là authority cho single use. Hai reset đồng thời chỉ một transaction thành công. DB write lỗi thì rollback password/session/receipt, giữ OTP để retry. Sau commit xóa Redis proof best effort; nếu cleanup Redis lỗi, receipt đã consumed vẫn ngăn replay. Disable/role mutation và reset tăng recovery version, nên disable rồi re-enable không làm sống lại mã cũ.

## Email outbox

Schema `auth` có `recovery_challenges` và `recovery_mail_outbox`; request tạo challenge và CODE outbox trong cùng transaction. CODE payload được mã hóa AES-256-GCM bằng key dẫn xuất từ control secret; DB không lưu OTP plaintext. Mã hóa dùng nonce ngẫu nhiên và xác thực payload. Redis không lưu raw OTP.

Publisher poll 1 giây, mỗi tick tối đa 10 rows, `FOR UPDATE SKIP LOCKED` hỗ trợ nhiều worker. Email CODE chỉ gửi khi challenge/current user version còn hợp lệ và Redis proof còn tồn tại; expired/invalidated code chuyển EXPIRED. SMTP lỗi retry tối đa 4 attempts, backoff 1/2/4 giây; hết attempts chuyển FAILED. SENT/FAILED/EXPIRED xóa ciphertext. NOTICE sau reset không chứa password/OTP và retry có giới hạn riêng cho row đó.

SMTP acceptance không bảo đảm mailbox delivery. Crash sau SMTP acceptance trước DB commit có thể gửi email trùng; nó không khiến OTP dùng hai lần. Outbox terminal failure hiện cần operator quan sát; chưa có admin redrive endpoint. Chưa có cleanup job cho challenge/refresh history; cần retention cho receipt đã hết hạn, không xóa challenge còn hiệu lực hoặc mail pending.

Thiết kế recovery tham khảo [OWASP Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html): response chung, random expiring proof, single use, giới hạn đoán mã, reset không tự login và revoke sessions. Việc kiểm soát email mailbox và secret provisioning là điều kiện vận hành; tính năng này chưa thay thế MFA hoặc quy trình recovery tài khoản bị xâm nhập.
