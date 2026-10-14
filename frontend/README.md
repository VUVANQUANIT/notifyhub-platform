# NotifyHub Web

Angular 22 standalone application for the NotifyHub tenant workspace: login/logout, in-memory token lifecycle, email OTP recovery, account/tenant overview, campaign creation/CSV import/start/schedule and delivery reporting.

## Run locally

Use Node **24.19+** (or another version matching `package.json` engines) and npm **11+**. Node 16 cannot build this application. `.nvmrc` pins the verified runtime.

From the repository root, start PostgreSQL/Redis/MailHog and Auth/Gateway using [the Auth runbook](../docs/auth-service.md). Then:

For the sending/reporting workflow, also run Kafka/RabbitMQ, Campaign and Reporting with `authenticated-local,messaging`, and Notification with `local,worker,events`; use the messaging/delivery runbooks for broker settings. Starting only Auth/Gateway is enough for account/recovery screens, while campaign/reporting screens require their services. The isolated campaign browser smoke below launches the complete stack.

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

## Campaigns and reporting

After login, use **Chiến dịch** to filter/page campaigns, create an EMAIL or simulated SMS draft, upload a UTF-8 CSV and review accepted/duplicate/invalid counts and row errors. CSV limits are 2 MiB / 10,000 data records; use `email,name` for email or `phoneNumber,name` for SMS (international `+` prefix). A rejected file is shown as rejected even though the import endpoint returns 201. Starting requires at least one valid recipient and a DRAFT campaign. A chosen local date/time is converted to UTC; starting a future campaign confirms its schedule.

ADMIN/OPERATOR can create/import/start; VIEWER sees read-only screens. Gateway/service scopes remain authoritative. Retry of an ambiguous create retains its exact payload/key and freezes the form; start retries reuse their key. Neither operation retries automatically. Keys stay in component memory, so leaving the screen/reloading loses them: inspect the campaign list before resubmitting a create after an uncertain response. CSV re-upload creates another import record, with destination deduplication on the server.

**Báo cáo** shows tenant-wide counters, campaign summaries and paginated SENT/FAILED deliveries with safe failure codes. Report IDs link back to Campaign; there are no cross-service DB reads. Unknown expected/pending counts remain unknown. Reports are eventually consistent, and drafts or newly started campaigns may initially return 404. Refresh is manual; there is no polling loop. HTTP 403/404/409/413, network failure and 429/503 backoff have visible states. Server exception details are not displayed; user content is rendered as text.

To verify the complete browser workflow with real PostgreSQL/Redis/Kafka/RabbitMQ/MailHog and five JARs:

```powershell
powershell -NoProfile -File scripts/smoke-campaign-browser.ps1
```

Run from the repository root after installing frontend dependencies. Pass `-SkipBuild` after backend verify, or `-NodeExecutable <path-to-Node-24>` / `-JavaExecutable <actual-Java-21-executable>` if workstation defaults differ. This smoke owns a separate Compose project on 25432/26379/25673/29093/21025/28025 and JAR ports 28080–28084; Angular uses 4201. It validates email/SMS through completion/reporting, CSV row feedback, MailHog mail, cross-tenant 404 and VIEWER write denial. It stops its JVMs and removes its own containers/network without stopping existing application/other smoke stacks. Logs remain in `test-results/campaign-services`; no live traces/screenshots/video are captured. `npm run e2e:live` remains Auth-only; `npm run e2e:campaign` requires this full stack already running.

Implementation references: [Angular functional interceptors](https://angular.dev/guide/http/interceptors), [route guards](https://angular.dev/guide/routing/route-guards), [development proxy](https://angular.dev/tools/cli/serve).
