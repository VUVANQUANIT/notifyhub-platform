package com.vuvanquan.notifyhub.notification.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class DeliveryMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final DeliveryMetrics metrics = new DeliveryMetrics(registry);
    @BeforeEach void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    @Test void outcomes_attempt_duration_and_retries_are_published_only_after_commit() {
        metrics.attempt("EMAIL", "failure", 1000000); metrics.retry("EMAIL"); metrics.completed("EMAIL", "FAILED");
        assertThat(registry.get("notifyhub.delivery.completed").tag("channel", "EMAIL").tag("status", "FAILED").counter().count()).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(registry.get("notifyhub.delivery.attempts").tag("channel", "EMAIL").tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("notifyhub.delivery.retries").tag("channel", "EMAIL").counter().count()).isEqualTo(1);
        assertThat(registry.get("notifyhub.delivery.completed").tag("channel", "EMAIL").tag("status", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.get("notifyhub.delivery.provider.duration").tag("channel", "EMAIL").tag("outcome", "failure").timer().count()).isEqualTo(1);
    }
    @Test void rollback_does_not_publish_provider_or_terminal_results() {
        metrics.attempt("SMS", "success", 1000000); metrics.completed("SMS", "SENT");
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
        assertThat(registry.get("notifyhub.delivery.completed").tag("channel", "SMS").tag("status", "SENT").counter().count()).isZero();
        assertThat(registry.get("notifyhub.delivery.attempts").tag("channel", "SMS").tag("outcome", "success").counter().count()).isZero();
    }
    @Test void requires_real_transaction_to_prevent_uncommitted_counts() {
        TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> metrics.completed("SMS", "SENT")).isInstanceOf(IllegalStateException.class);
    }
}
