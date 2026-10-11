# NotifyHub Web

Angular 22 standalone application for the NotifyHub tenant workspace. The first slice implements login/logout, an in-memory token lifecycle, email OTP password recovery and a protected account/tenant overview. Campaign/import/reporting screens are the next slice.

## Run locally

Use Node **24.19+** (or another version matching `package.json` engines) and npm **11+**. Node 16 cannot build this application. `.nvmrc` pins the verified runtime.

From the repository root, start PostgreSQL/Redis/MailHog and Auth/Gateway using [the Auth runbook](../docs/auth-service.md). Then:

```powershell
cd frontend
npm ci
npm start
```

Open `http://127.0.0.1:4200`. Existing tenant members sign in with their tenant slug, email and password. Local registration is available through the backend API described in the Auth runbook; the UI does not assume public self-registration is enabled.

`proxy.conf.cjs` forwards `/api/**` to Gateway at `http://127.0.0.1:8080`. To target another Gateway:

```powershell
$env:NOTIFYHUB_GATEWAY_URL = 'http://127.0.0.1:18080'
npm start
```

Production builds are under `dist/notifyhub-web/browser`. The hosting server must fall back to `index.html` for client routes and reverse-proxy `/api/**` to Gateway on the **same origin**. The development proxy is not included in the production bundle. Serve the app over HTTPS and disable response caching for Auth requests. Gateway continues to enforce JWT/scopes; route guards only control navigation.

## Session behavior

- Access and refresh credentials stay in application memory. No credentials are written to localStorage, sessionStorage, URLs or cookies. Reloading or opening a new tab requires login again.
- Expiring access tokens are refreshed before protected requests, with a 15-second margin. Concurrent calls share one rotation. A late 401 reuses a token already rotated by another call; a protected request is retried at most once.
- Refresh 429/503 respects `Retry-After`; neither credentials nor OTPs are automatically retried. Invalid/replayed refresh credentials expire the local session. An ambiguous network failure also requires a new login, because replaying a possibly consumed refresh token can revoke its successor.
- Logout waits for an in-flight rotation and revokes the latest refresh credential before clearing local identity. If the service is unreachable, local state is still cleared; server revocation is not guaranteed.
- Forgot-password feedback remains generic for eligible, unknown and disabled accounts. Resend has a local 60-second cooldown and server rate limits remain authoritative. Codes stay strings to preserve leading zeros. Reset validates matching passwords (15 code points minimum, 128 UTF-16 code units maximum), then returns to login without auto-sign-in.
- No automatic session persistence, login MFA, email verification or cross-tab refresh coordination is implemented. In-memory credentials remain accessible to code running on the page; deployment still needs normal XSS/CSP controls.

## Verify

```powershell
npm run build
npm test
npx playwright install chromium
npm run e2e
```

Unit tests use Angular's HTTP testing backend to exercise refresh concurrency, late 401s, credential boundaries, session expiry, backoff and logout/rotation races. The regular Playwright suite uses controlled responses for deterministic browser error, recovery, reload and mobile tests.

For browser tests against **real** Gateway/Auth, PostgreSQL, Redis and MailHog, from the repository root with Docker running and frontend dependencies installed:

```powershell
powershell -NoProfile -File scripts/smoke-auth-browser.ps1
```

The script builds Auth/Gateway and starts an isolated `notifyhub-auth-browser` Compose project on ports 15432/16379/11025/18025; JARs listen on 18081/18080 and Angular on 4201. It creates a unique tenant, tests login and real refresh, reads a recovery OTP from MailHog, resets the password, confirms session revocation and logs in with the new password. It stops its JARs/containers afterwards and preserves test volumes/logs for diagnosis. Occupied smoke ports are rejected. Pass `-SkipBuild` after backend `verify`.

Live tests disable traces, video and screenshots to avoid retaining real OTP/password/token payloads. Logs are Git-ignored under `test-results/live-services`; local test signing/control keys are Git-ignored under `.env.browser-smoke/`. The normal E2E suite contains only synthetic credentials.

CI runs frontend install, production build, unit tests and the regular Chromium suite as `Frontend verify`. Real browser smoke is available locally; backend CI separately verifies infrastructure with Testcontainers.

Implementation references: [Angular functional interceptors](https://angular.dev/guide/http/interceptors), [route guards](https://angular.dev/guide/routing/route-guards), [development proxy](https://angular.dev/tools/cli/serve).
