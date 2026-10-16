# Local Kubernetes deployment and recovery

This is a reproducible single-node **k3d validation environment**. It is not an HA production cluster. The base application manifests provide non-root/read-only containers, resource limits, startup/readiness/liveness probes, private ClusterIP services, service-specific secrets and NetworkPolicies. The k3d overlay adds PVC-backed PostgreSQL/Redis/RabbitMQ/Kafka/Tempo and local MailHog. Database schemas have separate owning application roles. Only the frontend HTTPS NodePort is exposed; no public database, broker, MailHog, traces or actuator port is opened.

## Deploy

Prerequisites: Docker Desktop Linux engine, kubectl, k3d 5.9.0, Java 21/Maven wrapper, Node 24 and OpenSSL. Verify downloaded tool checksums against the official release. Run from the repository root:

```powershell
./scripts/deploy-k3d.ps1 -K3dExecutable k3d
# Local optimization after a successful, current backend Maven verify:
./scripts/deploy-k3d.ps1 -K3dExecutable k3d -Prebuilt
```

The script uses `.env.k3d/kubeconfig` exclusively and leaves the default context unchanged. A pre-existing cluster without this workspace kubeconfig is not reused automatically. Local key/credential files under `.env.k3d` are ignored by Git and Docker build contexts. Windows ACLs are restricted to the current user and SYSTEM; Unix permissions are private. Reruns reuse credentials/keys so PVC-backed databases remain accessible; deleting/regenerating secrets while retaining PVCs requires a deliberate password/key migration.

The initial `demo-team` ADMIN is provisioned over the private PostgreSQL operator channel after Flyway. Credentials are in `.env.k3d/admin-login.json`; no public registration window is enabled. Treat this as local bootstrap only. Subsequent members should use email invitations and enable MFA. No credentials, keys or tokens should be pasted into PRs, screenshots or chat.

Open `https://notifyhub.localhost:8443`. The 30-day self-signed certificate includes localhost and 127.0.0.1; verify/trust it locally as appropriate. Scripts do not install a root certificate or alter machine trust. Frontend Nginx serves SPA/assets and proxies `/api`/JWKS to Gateway under the same HTTPS origin, drops supplied forwarding chains and prevents API/HTML caching. CSP uses a fresh response nonce for Angular styles, blocks inline scripts/frames/objects and requires Angular Trusted Types policies. Critical CSS inlining is disabled to avoid inline stylesheet load handlers. No source maps are shipped.

Auth's proxy trust remains empty by default: IP quotas aggregate at Gateway, while account quotas remain separate. This is fail-closed and suitable for the local environment. A shared deployment must provision a verified proxy topology and appropriate quotas; never trust arbitrary forwarding headers. All mutations remain protected by server roles, live Auth membership checks or short-lived downstream JWT scope checks.

## Verify

```powershell
kubectl --kubeconfig .env.k3d/kubeconfig -n notifyhub get pods,pvc
kubectl kustomize infra/k8s/overlays/k3d | docker run --rm -i ghcr.io/yannh/kubeconform:v0.8.0 -strict -summary -kubernetes-version 1.35.0
./scripts/verify-k3d-network.ps1
kubectl --kubeconfig .env.k3d/kubeconfig -n notifyhub port-forward service/mailhog 38025:8025
# In another terminal, with Node 24 and frontend dependencies installed:
cd frontend
node node_modules/@playwright/test/cli.js test --config playwright.k3d.config.ts
```

The live browser test uses only generated local credentials and test recipients. It validates real HTTPS/CSP, email invitation/activation, MFA recovery, audit projection and email campaign completion. Traces can be inspected using `kubectl --kubeconfig .env.k3d/kubeconfig -n notifyhub port-forward deployment/tempo 33200:3200`. Do not publish that unauthenticated infrastructure endpoint. The network verification script requires a successful frontend → Gateway connection and rejects frontend access to database Service/pod IP, Redis, brokers and Auth directly; it also checks separate database role permissions. Ingress and egress policies are separate: a live probe caught overly broad access with a combined policy on the tested CNI, even though schema validation passed. Deployment now runs the positive/negative probes before reporting success. The final overlay contains 46 resources.

## Backup and recovery

Tempo uses a 1 GiB memory limit, a 768 MiB Go soft limit, one flush worker and small Parquet row groups for this local workload. The former 256 MiB limit could pass readiness and then OOM during block completion. Keep the PVC during recovery; check successful `block completed`/`flushing block` logs and query a known trace after a pod restart. These limits are local validation defaults, not production capacity estimates. The Compose tracing overlay uses the same bounds.

```powershell
./scripts/backup-k3d.ps1 -NodeExecutable node
./scripts/verify-k3d-restore.ps1 -Backup .env.k3d/backups/<archive>.nhb -NodeExecutable node
```

Backup uses a consistent PostgreSQL custom-format dump spanning all four service schemas, plus the signing/control keys and local credential metadata. The archive is encrypted/authenticated with AES-256-GCM using a separate random `.env.k3d/backup.key`. Keep that key in a separate protected/offsite location; losing it makes recovery impossible. Local archives on the same disk do not protect against workstation loss. Copy encrypted archives and the separately protected key to independent storage under your retention policy (for example daily/7 days, weekly/4 weeks), and schedule restore drills. The script deliberately does not send files to an external destination.

Restore verification authenticates the archive **before** executing SQL, restores with `--exit-on-error` into a fresh random `notifyhub-restore-*` namespace, queries all service schemas and removes only its own namespace/scratch files. It does not replace the running database. The verification uses `--no-owner --no-privileges`; a real cutover must restore/provision the application roles and schema ownership separately using the protected metadata. Rotate affected credentials after a security incident.

For actual recovery: stop dispatch/consumers, restore the database and matching Auth control/signing secrets into a new environment, restore application role grants/secrets, verify Flyway versions and JWT/JWKS, then resume producers/consumers gradually. Broker messages and database snapshots are not an atomic cross-system backup; outbox/inbox dedup protects known replay, but provider acceptance followed by a crash can still duplicate a send. Reconcile pending tasks/results and inspect DLQ/DLT before redrive. Measure RPO/RTO during drills; this repository does not claim zero data loss or an untested SLA.

## Shared/production environment

Patch the base manifests for your domain, trusted CA certificate, image registry and immutable image tags, external/HA databases/brokers, SMTP/Twilio credentials, external tracing/monitoring receiver and egress policies. Use workload identity/external secret management, database/broker TLS/ACLs and restricted operator RBAC. k3d enables K3s secret encryption at rest; Kubernetes Secret Base64 alone is not encryption. Apply the [Kubernetes encryption guidance](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/) to a managed/shared cluster as appropriate.

Use the staged signing-key rotation runbook, test backward compatibility before rollback and do not downgrade across incompatible migrations. The local overlay has one replica and local-path storage, so cluster deletion destroys local volumes. To pause without deleting it: `k3d cluster stop notifyhub`; resume using `k3d cluster start notifyhub` with the dedicated kubeconfig. Never use cluster deletion as routine cleanup when retaining project data.
