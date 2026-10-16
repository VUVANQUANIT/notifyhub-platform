package com.vuvanquan.notifyhub.campaign;

import com.vuvanquan.notifyhub.campaign.application.*;
import com.vuvanquan.notifyhub.campaign.domain.*;
import com.vuvanquan.notifyhub.campaign.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.vuvanquan.notifyhub.campaign.application.CampaignModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="campaign.scheduling.enabled=false")
class CampaignApiIT {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CampaignApplicationService service;
    @Autowired CampaignRepository campaigns;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http = HttpClient.newHttpClient();
    UUID tenant;
    UUID user;

    @BeforeEach void actor() { tenant = UUID.randomUUID(); user = UUID.randomUUID(); }

    @Test void create_import_start_survives_database_reload_and_retries() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = call("POST", "", payload(null), key, tenant);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(tree(created).get("id").asText());
        assertThat(tree(created).get("status").asText()).isEqualTo("DRAFT");
        assertThat(tree(call("POST", "", payload(null), key, tenant)).get("id").asText()).isEqualTo(id.toString());

        var imported = upload(id, "email,name\r\n ALICE@EXAMPLE.COM ,Alice\r\nalice@example.com,Dup\r\nbad,Invalid\r\n", tenant);
        assertThat(imported.statusCode()).isEqualTo(201);
        JsonNode batch = tree(imported);
        assertThat(batch.get("summary").get("validRows").asInt()).isEqualTo(1);
        assertThat(batch.get("summary").get("duplicateRows").asInt()).isEqualTo(1);
        assertThat(batch.get("summary").get("invalidRows").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload->>'importId' from campaign.outbox_events "
                + "where campaign_id=? and event_type='RecipientImportCompleted'", String.class, id))
                .isEqualTo(batch.get("id").asText());
        assertThat(tree(call("GET", "/" + id + "/imports/" + batch.get("id").asText(), null, null, tenant))
                .get("errors").size()).isEqualTo(1);

        var reimport = tree(upload(id, "email,name\nalice@example.com,Changed\nbob@example.com,Bob\n", tenant));
        assertThat(reimport.get("summary").get("duplicateRows").asInt()).isEqualTo(1);
        var recipients = tree(call("GET", "/" + id + "/recipients", null, null, tenant));
        assertThat(recipients.get("totalElements").asInt()).isEqualTo(2);
        var started = call("POST", "/" + id + "/start", "", "start-1", tenant);
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(tree(started).get("status").asText()).isEqualTo("RUNNING");
        assertThat(tree(call("POST", "/" + id + "/start", "", "start-1", tenant)).get("startedAt"))
                .isEqualTo(tree(started).get("startedAt"));
        assertThat(call("POST", "/" + id + "/start", "", "different-key", tenant).statusCode()).isEqualTo(409);
        assertThat(upload(id, "email\nnew@example.com\n", tenant).statusCode()).isEqualTo(409);
        assertThat(events(id, "CampaignStarted")).isEqualTo(1);
        assertThat(campaigns.findByTenantIdAndId(tenant, id).orElseThrow().toDomain().status())
                .isEqualTo(CampaignStatus.RUNNING);
    }

    @Test void tenant_cannot_read_modify_or_list_another_tenants_data() throws Exception {
        UUID id = create(null);
        var batch = tree(upload(id, "email\none@example.com\n", tenant));
        UUID other = UUID.randomUUID();
        assertThat(call("GET", "/" + id, null, null, other).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/" + id + "/recipients", null, null, other).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/" + id + "/imports/" + batch.get("id").asText(), null, null, other).statusCode()).isEqualTo(404);
        assertThat(upload(id, "email\nx@example.com\n", other).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/" + id + "/start", "", "s", other).statusCode()).isEqualTo(404);
        assertThat(tree(call("GET", "", null, null, other)).get("totalElements").asInt()).isZero();
    }

    @Test void invalid_input_and_missing_context_return_client_errors() throws Exception {
        assertThat(call("POST", "", "{\"name\":\"\",\"channel\":\"EMAIL\",\"body\":\"x\"}", "k", tenant)
                .statusCode()).isEqualTo(400);
        assertThat(call("POST", "", payload(null), null, tenant).statusCode()).isEqualTo(400);
        assertThat(call("GET", "?size=101", null, null, tenant).statusCode()).isEqualTo(400);
        var response = http.send(HttpRequest.newBuilder(uri("")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        UUID id = create(null);
        assertThat(call("POST", "/" + id + "/start", "", "s", tenant).statusCode()).isEqualTo(409);
        assertThat(events(id, "CampaignStarted")).isZero();
    }

    @Test void malformed_csv_is_a_persisted_rejected_import_without_partial_recipients() throws Exception {
        UUID id = create(null);
        var rejected = tree(upload(id, "email\ngood@example.com\n\"unterminated", tenant));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(tree(call("GET", "/" + id + "/recipients", null, null, tenant))
                .get("totalElements").asInt()).isZero();
        assertThat(events(id, "RecipientImportRejected")).isEqualTo(1);
    }

    @Test void same_create_key_with_different_body_conflicts_but_other_tenant_can_reuse_it() throws Exception {
        String key = "shared-key";
        assertThat(call("POST", "", payload(null), key, tenant).statusCode()).isEqualTo(201);
        assertThat(call("POST", "", payload(null).replace("Welcome", "Changed"), key, tenant).statusCode()).isEqualTo(409);
        assertThat(call("POST", "", payload(null), key, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test void concurrent_create_and_start_emit_each_event_once() throws Exception {
        Actor actor = new Actor(tenant, user);
        var command = new CreateCampaign("Concurrent", Channel.EMAIL, "Subject", "Body", null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creates = pool.invokeAll(List.of(
                    () -> service.create(actor, "concurrent", command),
                    () -> service.create(actor, "concurrent", command)));
            UUID id = ((CampaignView) creates.getFirst().get()).id();
            assertThat(((CampaignView) creates.getLast().get()).id()).isEqualTo(id);
            service.importCsv(actor, id, "email\none@example.com\n".getBytes(StandardCharsets.UTF_8));
            var starts = pool.invokeAll(List.of(
                    () -> service.start(actor, id, "start"),
                    () -> service.start(actor, id, "start")));
            for (var result : starts) assertThat(((CampaignView) result.get()).status()).isEqualTo(CampaignStatus.RUNNING);
            assertThat(events(id, "CampaignCreated")).isEqualTo(1);
            assertThat(events(id, "CampaignStarted")).isEqualTo(1);
        }
    }

    @Test void concurrent_reimports_deduplicate_and_import_racing_start_cannot_mutate_after_start() throws Exception {
        Actor actor = new Actor(tenant, user);
        UUID id = create(null);
        byte[] csv = "email\none@example.com\n".getBytes(StandardCharsets.UTF_8);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var batches = pool.invokeAll(List.of(
                    () -> service.importCsv(actor, id, csv),
                    () -> service.importCsv(actor, id, csv)));
            long accepted = 0;
            for (var result : batches) accepted += ((ImportView) result.get()).summary().validRows();
            assertThat(accepted).isEqualTo(1);
            var race = pool.invokeAll(List.<Callable<String>>of(
                    () -> { service.start(actor, id, "s"); return "started"; },
                    () -> {
                        try {
                            service.importCsv(actor, id, "email\ntwo@example.com\n".getBytes(StandardCharsets.UTF_8));
                            return "imported";
                        } catch (IllegalStateException exception) { return "locked"; }
                    }));
            assertThat(race.getFirst().get()).isEqualTo("started");
            assertThat(race.getLast().get()).isIn("imported", "locked");
            assertThat(service.get(actor, id).status()).isEqualTo(CampaignStatus.RUNNING);
            assertThatThrownBy(() -> service.importCsv(actor, id, csv)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void database_enforces_uniqueness_foreign_keys_and_optimistic_locking() throws Exception {
        UUID id = create(null);
        var batch = tree(upload(id, "email\none@example.com\n", tenant));
        UUID batchId = UUID.fromString(batch.get("id").asText());
        assertThatThrownBy(() -> jdbc.update("""
                insert into campaign.campaign_recipients
                (id,tenant_id,campaign_id,channel,destination,personalization,source_import_id)
                values (?, ?, ?, 'EMAIL', 'one@example.com', '{}', ?)
                """, UUID.randomUUID(), tenant, id, batchId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into campaign.campaign_recipients
                (id,tenant_id,campaign_id,channel,destination,personalization,source_import_id)
                values (?, ?, ?, 'EMAIL', 'two@example.com', '{}', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), id, batchId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        CampaignEntity stale = campaigns.findByTenantIdAndId(tenant, id).orElseThrow();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var fresh = campaigns.findByTenantIdAndId(tenant, id).orElseThrow();
            fresh.name = "New name";
            campaigns.saveAndFlush(fresh);
        });
        stale.name = "Stale update";
        assertThatThrownBy(() -> campaigns.saveAndFlush(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test void processing_import_blocks_start_and_database_blocks_second_processing_import() throws Exception {
        UUID id = create(null);
        upload(id, "email\none@example.com\n", tenant);
        insertProcessing(id);
        assertThatThrownBy(() -> insertProcessing(id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(call("POST", "/" + id + "/start", "", "s", tenant).statusCode()).isEqualTo(409);
        assertThat(upload(id, "email\nanother@example.com\n", tenant).statusCode()).isEqualTo(409);
    }

    @Test void scheduled_campaign_activates_once_only_when_due() throws Exception {
        UUID id = create(Instant.now().plusSeconds(3600));
        upload(id, "email\none@example.com\n", tenant);
        assertThat(tree(call("POST", "/" + id + "/start", "", "s", tenant)).get("status").asText())
                .isEqualTo("SCHEDULED");
        service.activateDue(tenant, id);
        assertThat(events(id, "CampaignStarted")).isZero();
        jdbc.update("update campaign.campaigns set scheduled_at=created_at where id=?", id);
        service.activateDue(tenant, id);
        service.activateDue(tenant, id);
        assertThat(events(id, "CampaignStarted")).isEqualTo(1);
        assertThat(service.get(new Actor(tenant,user), id).status()).isEqualTo(CampaignStatus.RUNNING);
    }

    private void insertProcessing(UUID id) {
        jdbc.update("""
                insert into campaign.recipient_imports (id,tenant_id,campaign_id,requested_by,created_at,status)
                values (?, ?, ?, ?, now(), 'PROCESSING')
                """, UUID.randomUUID(), tenant, id, user);
    }

    @Test void rollback_reverts_both_campaign_transition_and_outbox_insert() throws Exception {
        UUID id = create(null);
        upload(id, "email\none@example.com\n", tenant);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.start(new Actor(tenant, user), id, "s");
            throw new IllegalStateException("Simulated transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(new Actor(tenant, user), id).status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(events(id, "CampaignStarted")).isZero();
    }
    private long events(UUID id, String type) {
        return jdbc.queryForObject("select count(*) from campaign.outbox_events where campaign_id=? and event_type=?",
                Long.class, id, type);
    }
    private UUID create(Instant scheduledAt) throws Exception {
        var response = call("POST", "", payload(scheduledAt), UUID.randomUUID().toString(), tenant);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return UUID.fromString(tree(response).get("id").asText());
    }
    private String payload(Instant scheduledAt) {
        return json.writeValueAsString(new CreateCampaign("Welcome", Channel.EMAIL, "Hello", "Hello {{name}}", scheduledAt));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/campaigns" + path); }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, String body, String key, UUID tenant) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("X-Tenant-Id", tenant.toString())
                .header("X-User-Id", user.toString()).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(UUID id, String csv, UUID tenant) throws Exception {
        String boundary = "notifyhub-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recipients.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri("/" + id + "/imports"))
                .header("X-Tenant-Id", tenant.toString()).header("X-User-Id", user.toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
