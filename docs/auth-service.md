# Auth, JWT và tenant isolation

Auth sở hữu schema PostgreSQL `auth`: tenant, user, role, refresh session và các hash refresh token. Gateway, Campaign và Reporting kiểm tra chữ ký, issuer, audience và scope; tenant/user được lấy từ JWT, bỏ qua `X-Tenant-Id`/`X-User-Id` ở chế độ JWT.

## Chạy trên máy phát triển

Build bằng `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify` trong thư mục `backend`. Khởi động dependencies bằng `docker compose up -d postgres redis mailhog`, rồi chạy mỗi lệnh trong một terminal, tại thư mục gốc repo:

```powershell
java -jar backend/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar backend/campaign-service/target/campaign-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=authenticated-local
java -jar backend/reporting-service/target/reporting-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=authenticated-local
```

`authenticated-local` có DB defaults và bind loopback như `local`, nhưng luôn yêu cầu JWT. Không kết hợp hai profile này. Campaign/Reporting `local` cũ vẫn dành cho smoke messaging bằng actor headers; nó không dùng để thử Auth. Gateway luôn xác thực JWT, kể cả profile `local`.

Auth local tạo RSA 3072-bit tại `.env.auth-local-private.pem` nếu chưa có; file này bị Git ignore và được dùng lại sau restart. Cả bốn service mặc định dùng issuer `http://127.0.0.1:8081`, audience `notifyhub`. Có thể đặt `JWT_ISSUER_URI` đồng nhất và `JWT_JWK_SET_URI` trỏ tới Auth khi đổi port hoặc chạy sau reverse proxy. Auth chỉ cho HTTP issuer loopback với profile `local`.

Chạy kiểm tra qua Gateway và hai service trực tiếp:

```powershell
powershell -NoProfile -File scripts/smoke-auth.ps1
```

Smoke tạo hai tenant độc lập, ADMIN/VIEWER, campaign SMS ở trạng thái DRAFT; kiểm tra JWT/JWKS thật, quyền đọc/ghi, forged headers, cross-tenant 404, refresh rotation/replay và logout. Script không in password/token. Nó không gửi notification; smoke delivery và integration messaging suite kiểm chứng luồng gửi riêng.

## API

Các POST/PUT nhận `application/json`; token được trả trong JSON, không đặt cookie. Những endpoint nhận credential chỉ hỗ trợ JSON, vì vậy browser simple form không đăng nhập/refresh được. Protected POST/PUT dùng `Authorization: Bearer <accessToken>`; không có session/form/basic authentication.

| Method/path | Credential / quyền | Nội dung |
|---|---|---|
| GET `/.well-known/jwks.json` | Public | RSA public JWK, `kid`, `alg=RS256`; không trả private parameters |
| POST `/api/auth/register` | Public, registration phải bật | `tenantSlug`, `tenantName`, `email`, `displayName`, `password`; tạo tenant và ADMIN trong một transaction |
| POST `/api/auth/login` | Public | `tenantSlug`, `email`, `password`; sai/thiếu/disabled trả cùng lỗi 401 |
| POST `/api/auth/refresh` | Opaque credential trong body | `refreshToken`; trả access/refresh mới |
| POST `/api/auth/logout` | Opaque credential trong body | `refreshToken`; revoke cả session, trả 204, idempotent với credential hợp lệ về định dạng |
| POST `/api/auth/password/forgot` | Public, Redis limits | `tenantSlug`, `email`; trả 202 chung với `challengeId`; email OTP qua outbox |
| POST `/api/auth/password/reset` | OTP body credential, Redis limits | `challengeId`, `code` 8 chữ số, `password`; trả 204, đổi mật khẩu và revoke refresh sessions |
| GET `/api/auth/me` | JWT | User hiện tại, DB kiểm tra user/tenant enabled |
| GET `/api/auth/tenant` | JWT | Tenant hiện tại |
| GET `/api/auth/users?page=0&size=20` | ADMIN, `users:read` | Danh sách chỉ trong tenant, size 1..100 |
| POST `/api/auth/users` | ADMIN, `users:write` | `email`, `displayName`, `password`, `role`; trả 201 |
| PUT `/api/auth/users/{id}/role` | ADMIN, `users:write` | `role`: ADMIN/OPERATOR/VIEWER |
| PUT `/api/auth/users/{id}/enabled` | ADMIN, `users:write` | `enabled`: boolean |

Register/login/refresh trả `tokenType`, `accessToken`, `expiresIn`, `refreshToken`, `refreshExpiresAt`. Register trả 200; trùng tenant slug hoặc email trong tenant trả 409. Email chuẩn hóa lowercase/trim và unique theo tenant; cùng email có thể thuộc hai tenant. Tenant slug gồm 3..63 ký tự lowercase, chữ số và dấu `-`, đầu/cuối là chữ hoặc số. Password giữ nguyên khoảng trắng, tối thiểu 15 code points và tối đa 128 UTF-16 code units.

Ví dụ lấy token trong PowerShell:

```powershell
$credentials = @{
    tenantSlug='demo-team'; tenantName='Demo Team'
    email='admin@example.com'; displayName='Admin'
    password='choose a long unique passphrase'
}
$tokens = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/register `
    -ContentType application/json -Body ($credentials | ConvertTo-Json)
$headers = @{Authorization="Bearer $($tokens.accessToken)"}
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/auth/me -Headers $headers
```

## Role và lifecycle

| Role | Scope |
|---|---|
| ADMIN | `campaigns:read campaigns:write reports:read users:read users:write` |
| OPERATOR | `campaigns:read campaigns:write reports:read` |
| VIEWER | `campaigns:read reports:read` |

User quản trị chỉ thao tác trong tenant của token; user thuộc tenant khác trả 404. Auth kiểm tra role trong DB khi quản trị, nên token ADMIN cũ không còn quản trị được sau demotion. Role/enabled thay đổi thu hồi toàn bộ refresh sessions của user. Tenant row lock đảm bảo hai thao tác đồng thời không thể hạ quyền hoặc disable admin enabled cuối cùng.

Access token ký RS256, mặc định 5 phút, chứa `iss`, `aud`, `sub` UUID, `tenant_id` UUID, `scope`, `roles`, `jti`, `iat`, `exp`. Auth cấu hình không cho access TTL quá 15 phút. Password dùng PBKDF2-HMAC-SHA256, 600.000 iterations, salt ngẫu nhiên 16 bytes và encoder ID lưu cùng hash. Chính sách iteration tham khảo [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Refresh token có 256 bits ngẫu nhiên; DB chỉ lưu SHA-256 hash. Session có hạn tuyệt đối 7 ngày, rotation không kéo dài hạn. Refresh consume token và insert successor trong cùng transaction; lỗi DB rollback cả hai. Hash consumed được giữ lại để phát hiện replay: dùng lại token cũ thu hồi toàn bộ session, kể cả successor, nhưng không thu hồi các session đăng nhập khác. Client phải serialize refresh requests; hai request cùng một token khiến request sau bị coi là replay. Transaction trả kết quả thất bại rồi API mới ném lỗi 401, để revocation được commit. Rotation tham khảo [OWASP OAuth2](https://cheatsheetseries.owasp.org/cheatsheets/OAuth2_Cheat_Sheet.html).

Logout, demotion và disable thu hồi refresh ngay. Campaign/Reporting chỉ kiểm JWT và scope, không đọc Auth DB: access token đã phát hành vẫn có hiệu lực đến hết hạn, cùng clock skew mặc định của Spring Security. Đây là giới hạn revocation hiện tại. Xem [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) về issuer, audience, JWK URI và token validation.

## Cấu hình môi trường chung

- Auth: `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD`, `AUTH_SIGNING_KEY_PATH`, `JWT_ISSUER_URI`, `JWT_AUDIENCE`.
- Auth controls: `AUTH_CONTROL_SECRET_PATH`, Redis/SMTP connection settings; local có defaults và tạo secret file được Git ignore. Ngoài local phải provision control secret riêng. Xem [rate limiting và OTP recovery](auth-controls.md) để cấu hình quotas, proxy IP, mail outbox và reset.
- Gateway/Campaign/Reporting: `JWT_ISSUER_URI`, `JWT_AUDIENCE`; `JWT_JWK_SET_URI` có thể là URL Auth nội bộ. Mặc định production JWK URI là `${JWT_ISSUER_URI}/.well-known/jwks.json`; không cần discovery server khi khởi động.
- Gateway upstream: `AUTH_SERVICE_URI`, `CAMPAIGN_SERVICE_URI`, `REPORTING_SERVICE_URI`.
- Production Auth yêu cầu issuer HTTPS và file private key PKCS#8 PEM RSA ít nhất 2048 bits đã được provision ngoài app. Mount read-only, giới hạn quyền file/ACL cho service account. Auth không tự sinh key ngoài `local`; không commit key.
- `AUTH_REGISTRATION_ENABLED` mặc định false ngoài local. Tenant bootstrap cần bật có kiểm soát, tạo admin rồi tắt; local mặc định true và có thể tắt bằng biến này. Chưa có invitation/provisioning admin API.
- Không kết hợp `local` với `prod`; Campaign/Reporting cũng không cho kết hợp `local` với `authenticated-local`.

## Kiểm thử và phần còn lại

Unit tests kiểm tra normalization/password/admin invariant và key persistence/weak key/prod safeguards. PostgreSQL + HTTP integration tests kiểm tra JWT/JWKS thật, sai issuer/audience/signature/expiry, generic login failure, tenant isolation, RBAC, last-admin concurrency, refresh rotation/replay/concurrency/rollback/expiry, disable, logout, JSON-only credentials và no-session/no-store. Gateway integration tests tải JWKS từ một HTTP upstream thật và kiểm chữ ký/issuer/audience/expiry/scope.

Redis rate limiting và OTP password reset qua email đã triển khai; xem [Auth controls](auth-controls.md). Login MFA, xác minh email khi đăng ký, đổi password bằng password hiện tại, invitation, audit identity events, rotation nhiều signing key và history cleanup job còn pending. Trước triển khai chung cần provision RSA/control secrets, Redis/SMTP với ACL/TLS/network isolation phù hợp và cấu hình trusted proxy chính xác. History cần retention/cleanup cho expired sessions; không xóa consumed hash hoặc challenge còn hạn. Frontend/token storage vẫn là hạng mục tiếp theo.
