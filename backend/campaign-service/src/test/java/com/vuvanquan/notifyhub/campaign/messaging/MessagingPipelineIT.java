package com.vuvanquan.notifyhub.campaign.messaging;

import com.vuvanquan.notifyhub.campaign.application.*;
import com.vuvanquan.notifyhub.campaign.domain.Channel;
import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static com.vuvanquan.notifyhub.campaign.messaging.MessagingConfiguration.*;

@Testcontainers
@ActiveProfiles({"local", "messaging"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "campaign.scheduling.enabled=false", "campaign.messaging.polling-enabled=false",
        "campaign.messaging.batch-size=2", "logging.level.org.apache.kafka=WARN"})
class MessagingPipelineIT {
    private static final String TOPIC = "notifyhub.campaign.events.v1";
    private static final String DLT = TOPIC + ".dlt";
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    // Kafka 3.9 also derives controller advertisements from listeners; wildcard addresses are invalid.
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    @Autowired CampaignApplicationService campaigns;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxPublisher outbox;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired DeliveryTaskPublisher publisher;
    @Autowired KafkaTemplate<Object, Object> kafka;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqp;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DeliveryResultInbox results;
    @Autowired CampaignCompletion completion;
    Actor actor;

    @BeforeEach void setup() {
        // Publishers intentionally drain all pending rows; keep each scenario's database isolated.
        jdbc.execute("TRUNCATE campaign.campaigns CASCADE");
        actor = new Actor(UUID.randomUUID(), UUID.randomUUID());
        amqp.purgeQueue(EMAIL_QUEUE);
        amqp.purgeQueue(SMS_QUEUE);
    }

    @Test void email_pipeline_batches_replays_and_concurrent_publishers_do_not_duplicate_tasks() throws Exception {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,Alice\nb@example.com,Bob\nc@example.com,Charlie\n");
        publishEventsAndAwaitJob(id);
        assertThat(dispatcher.dispatchOneBatch()).isTrue();
        assertThat(count("delivery_tasks", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM campaign.dispatch_jobs WHERE campaign_id=?",
                Boolean.class, id)).isTrue();
        assertThat(dispatcher.dispatchOneBatch()).isTrue();
        assertThat(count("delivery_tasks", id)).isEqualTo(3);
        String event = startedEnvelope(id);
        sendAndAwaitConsumed(event, id.toString());
        sendAndAwaitConsumed(event, id.toString());
        assertThat(dispatcher.dispatchOneBatch()).isFalse();
        assertThat(count("dispatch_jobs", id)).isEqualTo(1);
        assertThat(count("delivery_tasks", id)).isEqualTo(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Void>>of(
                    () -> { while (publisher.publishOne()) {} return null; },
                    () -> { while (publisher.publishOne()) {} return null; }));
            for (var result : results) result.get();
        }
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            var message = rabbit.receive(EMAIL_QUEUE, 5000);
            assertThat(message).isNotNull();
            var task = json.readValue(message.getBody(), SendNotificationTask.class);
            assertThat(task.tenantId()).isEqualTo(actor.tenantId());
            assertThat(task.campaignId()).isEqualTo(id);
            assertThat(task.body()).isIn("Hello Alice", "Hello Bob", "Hello Charlie");
            assertThat(task.correlationId()).isEqualTo(json.readValue(event, EventEnvelope.class).correlationId());
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(task.notificationId().toString());
            assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            ids.add(task.notificationId());
        }
        assertThat(ids).hasSize(3);
        assertThat(rabbit.receive(EMAIL_QUEUE)).isNull();
        assertThat(rabbit.receive(SMS_QUEUE)).isNull();
    }

    @Test void sms_uses_its_own_queue_and_has_no_subject() throws Exception {
        UUID id = start(Channel.SMS, "phoneNumber,name\n+84901234567,Quan\n");
        publishEventsAndAwaitJob(id);
        dispatcher.dispatchOneBatch();
        assertThat(publisher.publishOne()).isTrue();
        var message = rabbit.receive(SMS_QUEUE, 5000);
        assertThat(message).isNotNull();
        var task = json.readValue(message.getBody(), SendNotificationTask.class);
        assertThat(task.channel()).isEqualTo("SMS");
        assertThat(task.subject()).isNull();
        assertThat(task.body()).isEqualTo("Hello Quan");
        assertThat(rabbit.receive(EMAIL_QUEUE)).isNull();
    }

    @Test void campaign_waits_for_all_results_and_completes_once_despite_concurrent_replays() throws Exception {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,A\nb@example.com,B\n");
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        var events = resultEvents(id, "SENT");
        results.accept(events.getFirst());
        assertThat(campaignStatus(id)).isEqualTo("RUNNING");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> { results.accept(events.getLast()); return null; });
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        results.accept(events.getFirst());
        assertThat(campaignStatus(id)).isEqualTo("COMPLETED");
        assertThat(count("delivery_results", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign.outbox_events WHERE campaign_id=? AND event_type='CampaignCompleted'", Long.class, id)).isEqualTo(1);
        assertThat(completion.finalizeOne()).isFalse();
    }

    @Test void failure_waits_for_remaining_recipients_then_commits_failed_campaign_and_outbox() {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,A\nb@example.com,B\n");
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        var failed = resultEvents(id, "FAILED");
        results.accept(failed.getFirst());
        assertThat(campaignStatus(id)).isEqualTo("RUNNING");
        results.accept(resultEvents(id, "SENT").getLast());
        assertThat(campaignStatus(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM campaign.outbox_events WHERE campaign_id=? AND event_type='CampaignFailed'", String.class, id))
                .contains("\"failed\": 1", "\"sent\": 1");
    }

    @Test void results_before_final_dispatch_batch_are_completed_by_sweep() {
        UUID id = start(Channel.SMS, "phoneNumber,name\n+84901234567,A\n+84901234568,B\n");
        publishEventsAndAwaitJob(id);
        dispatcher.dispatchOneBatch(); // Exactly batchSize: completion is recorded by the next empty batch.
        resultEvents(id, "SENT").forEach(results::accept);
        assertThat(campaignStatus(id)).isEqualTo("RUNNING");
        dispatcher.dispatchOneBatch();
        assertThat(completion.finalizeOne()).isTrue();
        assertThat(campaignStatus(id)).isEqualTo("COMPLETED");
    }

    @Test void rejects_wrong_tenant_unknown_notification_and_conflicting_terminal_result() {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,A\n");
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        var sent = resultEvents(id, "SENT").getFirst();
        var forged = new DeliveryResultEvent(sent.eventId(), sent.eventType(), 1, UUID.randomUUID(), id,
                sent.occurredAt(), sent.correlationId(), sent.payload());
        assertThatThrownBy(() -> results.accept(forged)).isInstanceOf(IllegalArgumentException.class);
        var unknown = new DeliveryResultEvent(UUID.randomUUID(), sent.eventType(), 1, actor.tenantId(), id,
                sent.occurredAt(), sent.correlationId(), new DeliveryResultEvent.Payload(UUID.randomUUID(), sent.payload().recipientId(),
                "EMAIL", "SENT", 1, "smtp-id", null));
        assertThatThrownBy(() -> results.accept(unknown)).isInstanceOf(IllegalArgumentException.class);
        results.accept(sent);
        assertThatThrownBy(() -> results.accept(resultEvents(id, "FAILED").getFirst())).isInstanceOf(IllegalArgumentException.class);
        assertThat(campaignStatus(id)).isEqualTo("COMPLETED");
        assertThat(count("delivery_results", id)).isEqualTo(1);
    }

    @Test void result_consumer_commits_kafka_offset_only_after_completion_transaction() throws Exception {
        UUID id = start(Channel.SMS, "phoneNumber,name\n+84901234567,A\n");
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        var event = resultEvents(id, "SENT").getFirst();
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            results.accept(event);
            throw new IllegalStateException("Database rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(campaignStatus(id)).isEqualTo("RUNNING");
        assertThat(count("delivery_results", id)).isZero();
        kafka.send("notifyhub.notification.events.v1", id.toString(), json.writeValueAsString(event)).get(15, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(campaignStatus(id)).isEqualTo("COMPLETED"));
        assertThat(count("delivery_results", id)).isEqualTo(1);
    }

    @Test void accepts_worker_clock_skew_but_rejects_wrong_channel_and_correlation() {
        UUID id = start(Channel.SMS, "phoneNumber,name\n+84901234567,A\n");
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        var event = resultEvents(id, "SENT").getFirst();
        var wrongCorrelation = new DeliveryResultEvent(event.eventId(), event.eventType(), 1, event.tenantId(), id,
                event.occurredAt(), UUID.randomUUID(), event.payload());
        assertThatThrownBy(() -> results.accept(wrongCorrelation)).isInstanceOf(IllegalArgumentException.class);
        var wrongChannel = new DeliveryResultEvent(event.eventId(), event.eventType(), 1, event.tenantId(), id,
                event.occurredAt(), event.correlationId(), new DeliveryResultEvent.Payload(event.payload().notificationId(),
                event.payload().recipientId(), "EMAIL", "SENT", 1, "smtp-id", null));
        assertThatThrownBy(() -> results.accept(wrongChannel)).isInstanceOf(IllegalArgumentException.class);
        results.accept(new DeliveryResultEvent(event.eventId(), event.eventType(), 1, event.tenantId(), id,
                event.occurredAt().minusSeconds(120), event.correlationId(), event.payload()));
        assertThat(campaignStatus(id)).isEqualTo("COMPLETED");
    }

    private String campaignStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM campaign.campaigns WHERE id=?", String.class, id);
    }

    private List<DeliveryResultEvent> resultEvents(UUID id, String status) {
        return jdbc.query("SELECT payload::text FROM campaign.delivery_tasks WHERE campaign_id=? ORDER BY recipient_id", (rs, row) -> {
            var task = json.readValue(rs.getString(1), SendNotificationTask.class);
            return new DeliveryResultEvent(UUID.randomUUID(), status.equals("SENT") ? "NotificationSent" : "NotificationFailed", 1,
                    task.tenantId(), task.campaignId(), Instant.now(), task.correlationId(),
                    new DeliveryResultEvent.Payload(task.notificationId(), task.recipientId(), task.channel(), status, 1,
                            status.equals("SENT") ? "provider-id" : null, status.equals("FAILED") ? "SmtpUnavailable" : null));
        }, id);
    }

    @Test void unroutable_rabbit_message_remains_pending_then_recovers() throws Exception {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,Alice\n");
        publishEventsAndAwaitJob(id);
        dispatcher.dispatchOneBatch();
        var binding = new Binding(EMAIL_QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, EMAIL_KEY, null);
        amqp.removeBinding(binding);
        try {
            publisher.publishOne();
            assertThat(jdbc.queryForObject("""
                    SELECT published_at IS NULL AND publish_attempts=1 AND last_error IS NOT NULL
                    FROM campaign.delivery_tasks WHERE campaign_id=?
                    """, Boolean.class, id)).isTrue();
            assertThat(rabbit.receive(EMAIL_QUEUE)).isNull();
        } finally { amqp.declareBinding(binding); }
        jdbc.update("UPDATE campaign.delivery_tasks SET next_attempt_at=now() WHERE campaign_id=?", id);
        publisher.publishOne();
        assertThat(rabbit.receive(EMAIL_QUEUE, 5000)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL AND last_error IS NULL FROM campaign.delivery_tasks WHERE campaign_id=?",
                Boolean.class, id)).isTrue();
    }

    @Test void rollback_after_broker_ack_replays_same_event_and_notification_identity() throws Exception {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,Alice\n");
        // Publish create and import first; the next record is CampaignStarted.
        outbox.publishOne();
        outbox.publishOne();
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(outbox.publishOne()).isTrue();
            throw new IllegalStateException("Simulate crash before database commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM campaign.outbox_events WHERE campaign_id=? AND event_type='CampaignStarted'",
                Boolean.class, id)).isTrue();
        publishEventsAndAwaitJob(id);
        sendAndAwaitConsumed(startedEnvelope(id), id.toString());
        dispatcher.dispatchOneBatch();
        assertThat(count("delivery_tasks", id)).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publishOne();
            throw new IllegalStateException("Simulate crash after RabbitMQ confirm");
        })).isInstanceOf(IllegalStateException.class);
        var first = rabbit.receive(EMAIL_QUEUE, 5000);
        assertThat(first).isNotNull();
        publisher.publishOne();
        var replay = rabbit.receive(EMAIL_QUEUE, 5000);
        assertThat(replay).isNotNull();
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getMessageProperties().getMessageId()).isEqualTo(first.getMessageProperties().getMessageId());
    }

    @Test void invalid_record_goes_to_dlt_without_creating_a_job() throws Exception {
        var invalid = new EventEnvelope(UUID.randomUUID(), "CampaignStarted", 1, actor.tenantId(),
                UUID.randomUUID(), Instant.now(), UUID.randomUUID(), Map.of());
        String payload = json.writeValueAsString(invalid);
        sendAndAwaitConsumed(payload, invalid.campaignId().toString());
        assertThat(count("dispatch_jobs", invalid.campaignId())).isZero();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", UUID.randomUUID().toString(),
                "auto.offset.reset", "earliest", "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(List.of(DLT));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (payload.equals(record.value())) return true;
                }
                return false;
            });
        }
    }

    @Test void kafka_outage_preserves_outbox_for_retry() throws Exception {
        UUID id = start(Channel.EMAIL, "email,name\na@example.com,Alice\n");
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            outbox.publishOne();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM campaign.outbox_events WHERE campaign_id=?
                    AND published_at IS NULL AND publish_attempts=1 AND last_error IS NOT NULL
                    """, Long.class, id)).isEqualTo(1);
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        jdbc.update("UPDATE campaign.outbox_events SET next_attempt_at=now() WHERE campaign_id=?", id);
        publishEventsAndAwaitJob(id);
        while (dispatcher.dispatchOneBatch()) {}
        while (publisher.publishOne()) {}
        assertThat(rabbit.receive(EMAIL_QUEUE, 5000)).isNotNull();
    }

    private UUID start(Channel channel, String csv) {
        var request = new CampaignModels.CreateCampaign("Messaging test", channel,
                channel == Channel.EMAIL ? "Hello {{name}}" : null, "Hello {{name}}", null);
        var campaign = campaigns.create(actor, UUID.randomUUID().toString(), request);
        campaigns.importCsv(actor, campaign.id(), csv.getBytes(StandardCharsets.UTF_8));
        campaigns.start(actor, campaign.id(), UUID.randomUUID().toString());
        return campaign.id();
    }

    private void publishEventsAndAwaitJob(UUID id) {
        while (outbox.publishOne()) {}
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("dispatch_jobs", id)).isEqualTo(1));
    }

    private long count(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign." + table + " WHERE campaign_id=?", Long.class, id);
    }

    private String startedEnvelope(UUID id) {
        return jdbc.queryForObject("""
                SELECT json_build_object('eventId',event_id,'eventType',event_type,'eventVersion',event_version,
                'tenantId',tenant_id,'campaignId',campaign_id,'occurredAt',occurred_at,
                'correlationId',correlation_id,'payload',payload)::text
                FROM campaign.outbox_events WHERE campaign_id=? AND event_type='CampaignStarted'
                """, String.class, id);
    }

    private void sendAndAwaitConsumed(String value, String key) throws Exception {
        var sent = kafka.send(TOPIC, key, value).get(15, TimeUnit.SECONDS).getRecordMetadata();
        var partition = new TopicPartition(TOPIC, sent.partition());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                var offsets = admin.listConsumerGroupOffsets("campaign-dispatcher-v1")
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                return offsets.containsKey(partition) && offsets.get(partition).offset() > sent.offset();
            });
        }
    }
}
