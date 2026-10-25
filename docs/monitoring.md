# NotifyHub Monitoring

Monitoring covers all five services, delivery throughput/failures/retries, database backlog, RabbitMQ queues/DLQ, Kafka consumer lag, HTTP/JVM metrics, a provisioned Grafana dashboard and local Alertmanager alerts. Tracing and audit are separate work.

## Local setup

Run from the repository root with Docker Desktop and Java 21:

```powershell
./scripts/setup-monitoring.ps1 -Kafka
docker compose --profile kafka up -d
./backend/mvnw.cmd -f backend/pom.xml verify
```

Setup generates a random scrape password once, preserves it on subsequent runs, and writes ignored files in `.env.monitoring/`. Omit `-Kafka` for RabbitMQ-only development: the Kafka discovery file stays empty and causes no false down alert. Run setup again with `-Kafka` when enabling Kafka; Prometheus discovers the change within 15 seconds.

Start Auth first, then run each JAR in its own terminal from the repository root:

```powershell
java -jar backend/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,monitoring
java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,monitoring
java -jar backend/campaign-service/target/campaign-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=authenticated-local,messaging,monitoring
java -jar backend/notification-service/target/notification-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,worker,events,monitoring
java -jar backend/reporting-service/target/reporting-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=authenticated-local,messaging,monitoring
```

Existing DB/broker environment variables apply. Defaults assume `.env.example` infrastructure credentials. Compose reads `.env`; Java does not. Export custom credentials in each Java terminal when needed. Campaign/Reporting `authenticated-local` uses real JWT; do not combine it with `local` actor headers.

The monitoring profile imports `configtree:.env.monitoring/`. For another working directory, set `MONITORING_CONFIG_DIR` to the absolute directory with a trailing slash. Environment variables `MONITORING_USERNAME` and `MONITORING_PASSWORD` can also provision credentials. Missing/blank credentials fail startup when this profile is active. Prometheus uses username `notifyhub-monitoring` and the same password as a Compose secret.

| URL | Purpose |
|---|---|
| http://localhost:3000/d/notifyhub-overview | Grafana dashboard **NotifyHub / NotifyHub Monitoring** |
| http://localhost:9090/targets | Five service targets, RabbitMQ and optional Kafka exporter |
| http://localhost:9090/alerts | Rule status and pending/firing alerts |
| http://localhost:9093 | Alertmanager groups and silences |

Use the existing Grafana account. A fresh Grafana volume defaults to admin/admin and requests a password change. The supplied Alertmanager `local` receiver has no external destination; external notifications require separately configured credentials and an authorized receiver.

Monitoring UI ports bind to localhost. `GET /actuator/prometheus` requires the dedicated HTTP Basic scrape identity; it cannot authorize business APIs. Business JWTs do not grant scrape access. Keep scrape traffic private and use TLS across hosts. RabbitMQ port 15692 and Kafka exporter port 9308 remain internal to Compose.

## Metrics

| Prometheus metric | Meaning |
|---|---|
| `notifyhub_delivery_completed_total{channel,status}` | Committed SENT/FAILED results; duplicate tasks and rollback do not increase it |
| `notifyhub_delivery_attempts_total{channel,outcome}` | Committed provider attempts, success/failure |
| `notifyhub_delivery_retries_total{channel}` | Committed retries after provider failure, excluding early duplicate retries |
| `notifyhub_delivery_provider_duration_seconds_*` | Provider duration histogram; includes success/failure, published after commit |
| `notifyhub_delivery_state{channel,status}` | Retained delivery records by state, including PENDING/RETRY_PENDING |
| `notifyhub_pipeline_pending{service,queue}` | Pending outbox/tasks/dispatch/result_outbox/handoffs/recovery_mail rows |
| `notifyhub_pipeline_oldest_seconds{service,queue}` | Oldest backlog age; handoff/recovery age starts after available_at, excluding future backoff |
| `notifyhub_campaign_running`, `notifyhub_campaign_running_oldest_seconds` | Running campaigns and age since started_at |
| `notifyhub_reporting_pending`, `notifyhub_reporting_unknown_expected` | Expected recipients missing terminal projections; reports awaiting expected counts tracked separately |
| `notifyhub_auth_recovery_mail_failed` | Retained failed recovery mail, not a rate counter |
| `notifyhub_monitoring_database_up`, `notifyhub_monitoring_snapshot_timestamp` | Cached snapshot health and collection time |
| `rabbitmq_queue_messages_ready`, `rabbitmq_queue_messages_unacked`, `rabbitmq_queue_consumers` | Queue ready/unacknowledged messages and consumers, including `.dlq` queues |
| `kafka_consumergroup_lag{consumergroup,topic,partition}` | Broker end-offset minus committed group offset, including inactive groups |
| `http_server_requests_seconds_*`, `jvm_memory_used_bytes` | HTTP volume/error/latency and JVM memory |

Worker counters reset on restart; use `rate`/`increase` and aggregate rates across instances. Counters describe activity since instrumentation was deployed, rather than historical totals. SMTP acceptance does not prove inbox delivery; DB rollback cannot undo a provider side effect.

Database gauges refresh every 15 seconds with one read-only SELECT per service and a five-second JDBC query timeout. Scrapes read cached values. Failed/incomplete polls invalidate the snapshot: business gauges become NaN and database_up becomes 0. Data older than 90 seconds is also unavailable. Tune `monitoring.database.poll-interval-ms` and `monitoring.database.max-age-ms` together. Queries aggregate retained history, so measure overhead and tune retention/polling for larger datasets. With multiple replicas, use `max`, rather than `sum`, across repeated DB snapshots; the supplied dashboard assumes one instance per service.

Labels use service, queue, fixed channel/status/outcome and broker group/topic/partition. No tenant/user/notification IDs, destinations or error messages are attached. Groups without committed offsets can have negative/uninitialized Kafka lag. The panel/alert clamps these values to zero; this does not prove that the consumer is caught up. Inspect membership/offset initialization when lag is unavailable.

## Alerts and response

Thresholds are local MVP defaults in `infra/prometheus/alerts.yml`; adjust them for campaign size and traffic.

| Alert | Trigger and response |
|---|---|
| ServiceDown / BrokerMetricsDown | Scrape down for 2m; inspect process/profile/password/network |
| DatabaseMetricsUnavailable | Healthy scrape but snapshot unhealthy/missing for 2m; inspect DB/migration/poll duration |
| PipelineStalled | Oldest backlog over 5m, sustained 5m; inspect owning table last_error, publishers and dependencies |
| CampaignStuck | RUNNING over 30m, sustained 10m; correlate dispatch/tasks/results and quarantine topics |
| DeliveryFailureRate | FAILED over 10%, at least 10 results/5m, sustained 5m; inspect providers/failure codes |
| RecoveryMailFailed | Retained failures for 5m; inspect SMTP and recovery outbox failure_code |
| DeadLetters | Nonempty RabbitMQ DLQ for 2m; investigate before redrive |
| QueueWithoutConsumers | Ready delivery messages and no consumers for 2m; inspect worker profile/listeners |
| KafkaConsumerLag | Group/topic lag over 100 for 5m; inspect consumer errors/DLT/DB latency |
| HttpServerErrors | 5xx over 5%, at least 20 requests/5m, sustained 5m; inspect logs/dependencies |

Alertmanager groups alerts and suppresses the matching database-metrics alert while a service-down alert fires. Add a receiver to `infra/alertmanager.yml` when external notification delivery is required.

## Verification

`mvnw verify` tests authenticated scrapes on all services, scrape-account isolation from business APIs, actual PostgreSQL snapshot SQL and committed/duplicate/rollback delivery counters. CI validates Compose/Prometheus/Alertmanager and executes promtool rule tests for firing/duration/no-traffic/optional-Kafka cases and every dashboard PromQL expression.

After the services and monitoring stack are running:

```powershell
./scripts/smoke-monitoring.ps1
```

This verifies endpoints, snapshot health, active Prometheus targets and alert rules without creating business data. Use existing workflow smoke scripts to generate delivery traffic; throughput/p95 panels need traffic and sufficient samples in the selected window.

References: [Spring Boot management](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html), [RabbitMQ 3.13 Prometheus](https://www.rabbitmq.com/docs/3.13/prometheus), [Kafka exporter](https://github.com/danielqsj/kafka_exporter).
