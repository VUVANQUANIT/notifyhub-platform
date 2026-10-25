package com.vuvanquan.notifyhub.reporting;

import com.vuvanquan.notifyhub.contracts.*;
import com.vuvanquan.notifyhub.reporting.projection.ReportProjection;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles({"local", "messaging"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "logging.level.org.apache.kafka=WARN")
class ReportingIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @Autowired ReportProjection projection;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired KafkaTemplate<Object, Object> kafka;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    UUID tenant;
    UUID campaign;
    @BeforeEach void identities() { tenant = UUID.randomUUID(); campaign = UUID.randomUUID(); }

    @Test void projects_real_kafka_results_and_campaign_progress_with_tenant_scoped_http_reports() throws Exception {
        var sent = result("SENT");
        var failed = result("FAILED");
        kafka.send("notifyhub.campaign.events.v1", campaign.toString(), json.writeValueAsString(progress("CampaignStarted", "RUNNING", 2))).get(15, TimeUnit.SECONDS);
        kafka.send("notifyhub.notification.events.v1", campaign.toString(), json.writeValueAsString(sent)).get(15, TimeUnit.SECONDS);
        kafka.send("notifyhub.notification.events.v1", campaign.toString(), json.writeValueAsString(failed)).get(15, TimeUnit.SECONDS);
        kafka.send("notifyhub.campaign.events.v1", campaign.toString(), json.writeValueAsString(progress("CampaignFailed", "FAILED", 2))).get(15, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var report = get(tenant, "/api/reports/campaigns/" + campaign);
            assertThat(report.statusCode()).isEqualTo(200);
            var body = json.readTree(report.body());
            assertThat(body.path("status").asString()).isEqualTo("FAILED");
            assertThat(body.path("sent").asLong()).isEqualTo(1);
            assertThat(body.path("failed").asLong()).isEqualTo(1);
            assertThat(body.path("pending").asLong()).isZero();
        });
        var deliveries = get(tenant, "/api/reports/campaigns/" + campaign + "/deliveries?status=FAILED&size=1");
        assertThat(deliveries.statusCode()).isEqualTo(200);
        assertThat(deliveries.body()).contains("SmtpUnavailable").doesNotContain("providerReference", "destination", "body");
        assertThat(json.readTree(deliveries.body()).path("totalElements").asLong()).isEqualTo(1);
        assertThat(get(UUID.randomUUID(), "/api/reports/campaigns/" + campaign).statusCode()).isEqualTo(404);
        assertThat(get(UUID.randomUUID(), "/api/reports/campaigns/" + campaign + "/deliveries").statusCode()).isEqualTo(404);
    }

    @Test void concurrent_duplicate_and_new_event_id_replays_do_not_inflate_counts() throws Exception {
        var event = result("SENT");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) calls.add(() -> { projection.delivery(event); return null; });
            for (var future : pool.invokeAll(calls)) future.get();
        }
        projection.delivery(new DeliveryResultEvent(UUID.randomUUID(), event.eventType(), 1, tenant, campaign,
                event.occurredAt(), event.correlationId(), event.payload()));
        var summary = get(tenant, "/api/reports/summary");
        assertThat(json.readTree(summary.body()).path("deliveries").asLong()).isEqualTo(1);
        assertThat(json.readTree(summary.body()).path("attempts").asLong()).isEqualTo(1);
        assertThat(json.readTree(get(UUID.randomUUID(), "/api/reports/summary").body()).path("deliveries").asLong()).isZero();
    }

    @Test void completion_before_start_and_deliveries_does_not_regress_status() throws Exception {
        var completed = progress("CampaignCompleted", "COMPLETED", 1);
        projection.progress(completed);
        projection.progress(progress("CampaignStarted", "RUNNING", 1));
        projection.delivery(result("SENT"));
        projection.progress(completed);
        var report = json.readTree(get(tenant, "/api/reports/campaigns/" + campaign).body());
        assertThat(report.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(report.path("expected").asLong()).isEqualTo(1);
        assertThat(report.path("pending").asLong()).isZero();
    }

    @Test void rejects_conflicting_notification_identity_and_rolls_back_the_other_tenant_projection() {
        var original = result("SENT");
        projection.delivery(original);
        UUID otherTenant = UUID.randomUUID();
        var conflict = new DeliveryResultEvent(UUID.randomUUID(), original.eventType(), 1, otherTenant, campaign,
                original.occurredAt(), original.correlationId(), original.payload());
        assertThatThrownBy(() -> projection.delivery(conflict)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.campaign_reports WHERE tenant_id=?", Long.class, otherTenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports WHERE notification_id=?", Long.class, original.payload().notificationId())).isEqualTo(1);
    }

    @Test void rejects_conflicting_completion_counts_and_keeps_known_count_when_legacy_start_is_replayed() throws Exception {
        projection.progress(progress("CampaignStarted", "RUNNING", 1));
        projection.progress(new CampaignProgressEvent(UUID.randomUUID(), "CampaignStarted", 1, tenant, campaign,
                Instant.now(), UUID.randomUUID(), Map.of("status", "RUNNING")));
        projection.delivery(result("SENT"));
        assertThatThrownBy(() -> projection.delivery(result("SENT"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection.progress(progress("CampaignCompleted", "COMPLETED", 2))).isInstanceOf(IllegalArgumentException.class);
        projection.progress(progress("CampaignCompleted", "COMPLETED", 1));
        assertThatThrownBy(() -> projection.progress(progress("CampaignFailed", "FAILED", 1))).isInstanceOf(IllegalArgumentException.class);
        var report = json.readTree(get(tenant, "/api/reports/campaigns/" + campaign).body());
        assertThat(report.path("expected").asLong()).isEqualTo(1);
        assertThat(report.path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test void database_rollback_then_replay_commits_projection_once() {
        var event = result("FAILED");
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            projection.delivery(event);
            throw new IllegalStateException("Crash before database commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports WHERE notification_id=?", Long.class, event.payload().notificationId())).isZero();
        projection.delivery(event);
        projection.delivery(event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports WHERE notification_id=?", Long.class, event.payload().notificationId())).isEqualTo(1);
    }

    @Test void invalid_version_is_confirmed_to_reporting_dlt_without_mutating_projection() throws Exception {
        var event = result("SENT");
        String invalid = json.writeValueAsString(event).replace("\"eventVersion\":1", "\"eventVersion\":2");
        kafka.send("notifyhub.notification.events.v1", campaign.toString(), invalid).get(15, TimeUnit.SECONDS);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", UUID.randomUUID().toString(),
                "auto.offset.reset", "earliest", "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(List.of("notifyhub.notification.events.v1.reporting.dlt"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) if (record.value().equals(invalid)) return true;
                return false;
            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.delivery_reports WHERE notification_id=?", Long.class, event.payload().notificationId())).isZero();
    }

    @Test void validates_pagination_filter_and_local_identity() throws Exception {
        projection.delivery(result("SENT"));
        assertThat(get(tenant, "/api/reports/campaigns?page=-1").statusCode()).isEqualTo(400);
        assertThat(get(tenant, "/api/reports/campaigns?size=101").statusCode()).isEqualTo(400);
        assertThat(get(tenant, "/api/reports/campaigns/" + campaign + "/deliveries?status=PENDING").statusCode()).isEqualTo(400);
        assertThat(json.readTree(get(tenant, "/api/reports/campaigns?size=1").body()).path("totalElements").asLong()).isEqualTo(1);
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reports/summary")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(400);
    }

    private DeliveryResultEvent result(String status) {
        return new DeliveryResultEvent(UUID.randomUUID(), status.equals("SENT") ? "NotificationSent" : "NotificationFailed", 1,
                tenant, campaign, Instant.now(), UUID.randomUUID(), new DeliveryResultEvent.Payload(UUID.randomUUID(), UUID.randomUUID(),
                "EMAIL", status, 1, status.equals("SENT") ? "smtp-id" : null, status.equals("FAILED") ? "SmtpUnavailable" : null));
    }
    private CampaignProgressEvent progress(String type, String status, long expected) {
        return new CampaignProgressEvent(UUID.randomUUID(), type, 1, tenant, campaign, Instant.now(), UUID.randomUUID(), Map.of("status", status, "expected", expected));
    }
    private HttpResponse<String> get(UUID tenant, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant.toString()).header("X-User-Id", UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
