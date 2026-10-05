package com.vuvanquan.notifyhub.notification.worker;

import com.rabbitmq.client.GetResponse;
import com.vuvanquan.notifyhub.contracts.DeliveryResultEvent;
import com.vuvanquan.notifyhub.notification.events.ResultPublisher;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.vuvanquan.notifyhub.notification.worker.WorkerConfiguration.*;

@Testcontainers
@ActiveProfiles({"local", "worker", "events"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notification.worker.polling-enabled=false", "notification.events.polling-enabled=false",
        "notification.worker.max-attempts=3", "logging.level.org.apache.kafka=WARN",
        "notification.worker.initial-retry-delay=10s", "notification.worker.max-retry-delay=20s"})
@Import(NotificationWorkerIT.TimeConfiguration.class)
class NotificationWorkerIT {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
    @Container static final GenericContainer<?> MAIL = new GenericContainer<>("mailhog/mailhog:v1.0.1")
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/api/v2/messages").forPort(8025));

    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
        registry.add("spring.mail.host", MAIL::getHost);
        registry.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqp;
    @Autowired ObjectMapper json;
    @Autowired DeliveryWorker worker;
    @Autowired HandoffPublisher publisher;
    @Autowired ResultPublisher results;
    @Autowired NotificationTaskListener listener;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean EmailDeliveryProvider email;
    @MockitoSpyBean SimulatedSmsProvider sms;
    @MockitoSpyBean HandoffStore handoffs;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void resetState() throws Exception {
        listeners.stop();
        for (String queue : List.of(EMAIL_QUEUE, SMS_QUEUE, EMAIL_DLQ, SMS_DLQ)) amqp.purgeQueue(queue);
        jdbc.execute("TRUNCATE notification.result_outbox, notification.delivery_handoffs, notification.deliveries");
        http.send(HttpRequest.newBuilder(mailUri("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        reset(email, sms, AopTestUtils.getUltimateTargetObject(handoffs));
        clock.set(Instant.now());
        listeners.start();
    }

    @Test void email_is_sent_to_mailhog_and_committed_with_provider_reference() throws Exception {
        var task = task("EMAIL");
        send(task);
        awaitState(task, "SENT", 1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String messages = messages();
            assertThat(messages).contains("Hello Quan", task.subject(), task.notificationId().toString(), task.correlationId().toString());
            assertThat(mailCount()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("""
                SELECT provider_reference IS NOT NULL AND completed_at IS NOT NULL AND next_attempt_at IS NULL
                AND tenant_id=? AND campaign_id=? FROM notification.deliveries WHERE notification_id=?
                """, Boolean.class, task.tenantId(), task.campaignId(), task.notificationId())).isTrue();
        var envelope = jdbc.queryForObject("SELECT envelope::text FROM notification.result_outbox WHERE notification_id=?", String.class, task.notificationId());
        assertThat(envelope).doesNotContain(task.destination(), task.body());
        assertThat(json.readValue(envelope, DeliveryResultEvent.class).payload().status()).isEqualTo("SENT");
    }

    @Test void kafka_result_replays_same_identity_after_confirm_then_rollback_without_resending_email() throws Exception {
        var task = task("EMAIL");
        listeners.stop();
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(results.publishOne()).isTrue();
            throw new IllegalStateException("Crash after Kafka ack before DB commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM notification.result_outbox WHERE notification_id=?", Boolean.class, task.notificationId())).isTrue();
        results.publishOne();
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        var events = resultEvents(task, 2);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(events.get(1));
        assertThat(events.getFirst().correlationId()).isEqualTo(task.correlationId());
        verify(email, times(1)).send(any());
    }

    @Test void kafka_outage_keeps_result_pending_and_does_not_consume_provider_attempts() throws Exception {
        var task = task("SMS");
        listeners.stop();
        worker.deliver(json.writeValueAsBytes(task), "SMS");
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            results.publishOne();
            assertThat(jdbc.queryForObject("""
                    SELECT published_at IS NULL AND publish_attempts=1 AND last_error IS NOT NULL
                    FROM notification.result_outbox WHERE notification_id=?
                    """, Boolean.class, task.notificationId())).isTrue();
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        jdbc.update("UPDATE notification.result_outbox SET next_attempt_at=now() WHERE notification_id=?", task.notificationId());
        results.publishOne();
        assertThat(resultEvents(task, 1).getFirst().payload().attempts()).isEqualTo(1);
        verify(sms, times(1)).send(any());
    }

    @Test void delivery_and_result_outbox_rollback_together_and_final_failure_emits_only_one_result() throws Exception {
        var task = task("EMAIL");
        listeners.stop();
        doThrow(new DeliveryFailure("InvalidMailMessage", false)).when(email).send(any());
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            worker.deliver(json.writeValueAsBytes(task), "EMAIL");
            throw new IllegalStateException("Crash before transaction commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.result_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries", Long.class)).isZero();
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        results.publishOne();
        var event = resultEvents(task, 1).getFirst();
        assertThat(event.eventType()).isEqualTo("NotificationFailed");
        assertThat(event.payload().failureCode()).isEqualTo("InvalidMailMessage");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.result_outbox", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.delivery_handoffs", Long.class)).isEqualTo(1);
    }

    private List<DeliveryResultEvent> resultEvents(SendNotificationTask task, int expected) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", UUID.randomUUID().toString(),
                "auto.offset.reset", "earliest", "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(List.of("notifyhub.notification.events.v1"));
            var events = new ArrayList<DeliveryResultEvent>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    var event = json.readValue(record.value(), DeliveryResultEvent.class);
                    if (event.payload().notificationId().equals(task.notificationId())) {
                        assertThat(record.key()).isEqualTo(task.campaignId().toString());
                        events.add(event);
                    }
                }
                return events.size() >= expected;
            });
            return events;
        }
    }

    @Test void concurrent_duplicate_tasks_and_json_whitespace_do_not_resend() throws Exception {
        var task = task("EMAIL");
        listeners.stop();
        byte[] payload = json.writeValueAsBytes(task);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) calls.add(() -> { worker.deliver(payload, "EMAIL"); return null; });
            for (var future : pool.invokeAll(calls)) future.get();
        }
        worker.deliver((" \n" + json.writeValueAsString(task) + " \n").getBytes(StandardCharsets.UTF_8), "EMAIL");
        awaitState(task, "SENT", 1);
        assertThat(mailCount()).isEqualTo(1);
        verify(email, times(1)).send(any());
    }

    @Test void sms_is_simulated_and_never_calls_smtp() throws Exception {
        var task = task("SMS");
        send(task);
        awaitState(task, "SENT", 1);
        assertThat(jdbc.queryForObject("SELECT provider_reference FROM notification.deliveries WHERE notification_id=?",
                String.class, task.notificationId())).isEqualTo("sms-simulated:" + task.notificationId());
        verify(email, never()).send(any());
        assertThat(mailCount()).isZero();
    }

    @Test void transient_failure_waits_for_backoff_then_recovers() throws Exception {
        clock.set(Instant.parse("2026-10-03T00:00:00.123456789Z"));
        var task = task("EMAIL");
        doThrow(new DeliveryFailure("TemporaryFailure", true)).doCallRealMethod().when(email).send(any());
        send(task);
        awaitState(task, "RETRY_PENDING", 1);
        assertThat(publisher.publishOne()).isFalse();
        // An early duplicate does not bypass the durable due time.
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        verify(email, times(1)).send(any());
        clock.advance(Duration.ofSeconds(10));
        assertThat(publisher.publishOne()).isTrue();
        awaitState(task, "SENT", 2);
        assertThat(mailCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.delivery_handoffs WHERE published_at IS NULL", Long.class)).isZero();
    }

    @Test void early_retry_remains_durable_when_a_publisher_clock_is_ahead() throws Exception {
        var task = task("EMAIL");
        doThrow(new DeliveryFailure("TemporaryFailure", true)).doCallRealMethod().when(email).send(any());
        send(task);
        awaitState(task, "RETRY_PENDING", 1);
        var ahead = new HandoffPublisher(jdbc, rabbit, Clock.offset(clock, Duration.ofSeconds(10)));
        Boolean published = new TransactionTemplate(transactions).execute(status -> ahead.publishOne());
        assertThat(published).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT published_at IS NULL FROM notification.delivery_handoffs WHERE notification_id=?",
                Boolean.class, task.notificationId())).isTrue());
        verify(email, times(1)).send(any());
        clock.advance(Duration.ofSeconds(10));
        publisher.publishOne();
        awaitState(task, "SENT", 2);
        assertThat(mailCount()).isEqualTo(1);
    }

    @Test void real_smtp_outage_is_retryable_and_recovers_without_losing_the_task() throws Exception {
        var task = task("EMAIL");
        MAIL.getDockerClient().pauseContainerCmd(MAIL.getContainerId()).exec();
        try {
            send(task);
            awaitState(task, "RETRY_PENDING", 1);
            assertThat(jdbc.queryForObject("SELECT last_error FROM notification.deliveries WHERE notification_id=?",
                    String.class, task.notificationId())).isEqualTo("SmtpUnavailable");
        } finally { MAIL.getDockerClient().unpauseContainerCmd(MAIL.getContainerId()).exec(); }
        clock.advance(Duration.ofSeconds(10));
        publisher.publishOne();
        awaitState(task, "SENT", 2);
        assertThat(mailCount()).isEqualTo(1);
    }

    @Test void retry_exhaustion_is_persisted_and_goes_to_dlq_once_logically() throws Exception {
        var task = task("EMAIL");
        doThrow(new DeliveryFailure("ProviderUnavailable", true)).when(email).send(any());
        send(task);
        awaitState(task, "RETRY_PENDING", 1);
        clock.advance(Duration.ofSeconds(10));
        publisher.publishOne();
        awaitState(task, "RETRY_PENDING", 2);
        assertThat(publisher.publishOne()).isFalse();
        clock.advance(Duration.ofSeconds(20));
        publisher.publishOne();
        awaitState(task, "FAILED", 3);
        assertThat(publisher.publishOne()).isTrue();
        var dead = rabbit.receive(EMAIL_DLQ, 5000);
        assertThat(dead).isNotNull();
        assertThat(json.readValue(dead.getBody(), SendNotificationTask.class)).isEqualTo(task);
        assertThat(dead.getMessageProperties().getHeaders()).containsEntry("x-notifyhub-attempt", 3);
        assertThat(dead.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        worker.deliver(json.writeValueAsBytes(task), "EMAIL");
        verify(email, times(3)).send(any());
        assertThat(publisher.publishOne()).isFalse();
        assertThat(rabbit.receive(EMAIL_DLQ)).isNull();
    }

    @Test void permanent_failure_skips_retry_and_unroutable_dlq_publication_recovers() throws Exception {
        var task = task("EMAIL");
        doThrow(new DeliveryFailure("InvalidRecipient", false)).when(email).send(any());
        send(task);
        awaitState(task, "FAILED", 1);
        var binding = new Binding(EMAIL_DLQ, Binding.DestinationType.QUEUE, DEAD_EXCHANGE, EMAIL_KEY, null);
        amqp.removeBinding(binding);
        try {
            publisher.publishOne();
            assertThat(jdbc.queryForObject("""
                    SELECT published_at IS NULL AND publish_attempts=1 AND last_error IS NOT NULL
                    FROM notification.delivery_handoffs WHERE notification_id=?
                    """, Boolean.class, task.notificationId())).isTrue();
            assertThat(rabbit.receive(EMAIL_DLQ)).isNull();
        } finally { amqp.declareBinding(binding); }
        clock.advance(Duration.ofSeconds(1));
        publisher.publishOne();
        assertThat(rabbit.receive(EMAIL_DLQ, 5000)).isNotNull();
        verify(email, times(1)).send(any());
    }

    @Test void malformed_wrong_version_and_wrong_channel_messages_are_quarantined() throws Exception {
        var task = task("SMS");
        byte[] malformed = new byte[] {0, (byte) 0xff, 1};
        sendRaw(malformed, EMAIL_KEY);
        sendRaw(json.writeValueAsBytes(task), EMAIL_KEY);
        sendRaw(json.writeValueAsString(task).replace("\"taskVersion\":1", "\"taskVersion\":99")
                .getBytes(StandardCharsets.UTF_8), SMS_KEY);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(handoffCount()).isEqualTo(3));
        while (publisher.publishOne()) {}
        List<byte[]> rejected = List.of(rabbit.receive(EMAIL_DLQ, 5000).getBody(), rabbit.receive(EMAIL_DLQ, 5000).getBody());
        assertThat(rejected.stream().anyMatch(bytes -> Arrays.equals(bytes, malformed))).isTrue();
        assertThat(rabbit.receive(SMS_DLQ, 5000)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries", Long.class)).isZero();
        verify(email, never()).send(any());
        verify(sms, never()).send(any());
    }

    @Test void conflicting_identity_cannot_overwrite_another_tenants_delivery() throws Exception {
        var original = task("SMS");
        worker.deliver(json.writeValueAsBytes(original), "SMS");
        var conflict = new SendNotificationTask(original.notificationId(), 1, UUID.randomUUID(), original.campaignId(),
                original.recipientId(), original.correlationId(), "SMS", original.destination(), null, "Changed", original.createdAt());
        worker.deliver(json.writeValueAsBytes(conflict), "SMS");
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM notification.deliveries WHERE notification_id=?",
                UUID.class, original.notificationId())).isEqualTo(original.tenantId());
        awaitState(original, "SENT", 1);
        publisher.publishOne();
        assertThat(rabbit.receive(SMS_DLQ, 5000).getMessageProperties().getHeaders())
                .containsEntry("x-notifyhub-failure", "NotificationIdentityConflict");
        verify(sms, times(1)).send(any());
    }

    @Test void failed_handoff_transaction_is_nacked_and_redelivered_before_ack() throws Exception {
        listeners.stop();
        var task = task("EMAIL");
        doThrow(new DeliveryFailure("TemporaryFailure", true)).when(email).send(any());
        HandoffStore handoffTarget = AopTestUtils.getUltimateTargetObject(handoffs);
        doThrow(new DataAccessResourceFailureException("Simulated DB failure")).doCallRealMethod()
                .when(handoffTarget).retry(any(), any(), anyInt(), any(), anyString());
        send(task);
        rabbit.execute(channel -> {
            GetResponse delivery = channel.basicGet(EMAIL_QUEUE, false);
            assertThat(delivery).isNotNull();
            listener.email(inbound(delivery), channel);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries", Long.class)).isZero();
        assertThat(handoffCount()).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(amqp.getQueueInfo(EMAIL_QUEUE).getMessageCount()).isEqualTo(1));
        rabbit.execute(channel -> {
            GetResponse replay = channel.basicGet(EMAIL_QUEUE, false);
            assertThat(replay.getEnvelope().isRedeliver()).isTrue();
            listener.email(inbound(replay), channel);
            return null;
        });
        awaitState(task, "RETRY_PENDING", 1);
        assertThat(handoffCount()).isEqualTo(1);
        assertThat(rabbit.receive(EMAIL_QUEUE)).isNull();
    }

    @Test void crash_after_handoff_confirm_replays_the_same_dlq_message() throws Exception {
        var task = task("SMS");
        doThrow(new DeliveryFailure("InvalidRecipient", false)).when(sms).send(any());
        send(task);
        awaitState(task, "FAILED", 1);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(publisher.publishOne()).isTrue();
            throw new IllegalStateException("Crash after broker confirm");
        })).isInstanceOf(IllegalStateException.class);
        var first = rabbit.receive(SMS_DLQ, 5000);
        assertThat(first).isNotNull();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM notification.delivery_handoffs", Boolean.class)).isTrue();
        publisher.publishOne();
        var replay = rabbit.receive(SMS_DLQ, 5000);
        assertThat(replay).isNotNull();
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getMessageProperties().getMessageId()).isEqualTo(task.notificationId().toString());
    }

    private Message inbound(GetResponse response) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        return new Message(response.getBody(), properties);
    }

    private void send(SendNotificationTask task) throws Exception {
        sendRaw(json.writeValueAsBytes(task), task.channel().equals("EMAIL") ? EMAIL_KEY : SMS_KEY);
    }

    private void sendRaw(byte[] body, String key) throws Exception {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(EXCHANGE, key, MessageBuilder.withBody(body).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build(), correlation);
        assertThat(correlation.getFuture().get(10, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(correlation.getReturned()).isNull();
    }

    private void awaitState(SendNotificationTask task, String state, int attempts) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification.deliveries WHERE status=? AND attempts=? AND notification_id=?)
                """, Boolean.class, state, attempts, task.notificationId())).isTrue());
    }
    private long handoffCount() { return jdbc.queryForObject("SELECT count(*) FROM notification.delivery_handoffs", Long.class); }
    private SendNotificationTask task(String channel) {
        UUID id = UUID.randomUUID();
        return new SendNotificationTask(id, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                channel, channel.equals("EMAIL") ? "recipient@example.com" : "+84901234567",
                channel.equals("EMAIL") ? "Welcome " + id : null, "Hello Quan", clock.instant());
    }
    private URI mailUri(String path) { return URI.create("http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(8025) + path); }
    private String messages() throws Exception {
        return http.send(HttpRequest.newBuilder(mailUri("/api/v2/messages")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    private int mailCount() throws Exception { return json.readTree(messages()).get("total").asInt(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        void set(Instant value) { now = value; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
