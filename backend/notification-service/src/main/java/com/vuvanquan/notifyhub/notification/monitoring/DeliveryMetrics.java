package com.vuvanquan.notifyhub.notification.monitoring;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.util.concurrent.TimeUnit;

/** Publish business counters only after commit, so rollback and duplicate delivery do not inflate results. */
@Component
public class DeliveryMetrics {
    private final MeterRegistry registry;

    public DeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String channel : new String[]{"EMAIL", "SMS"}) {
            for (String outcome : new String[]{"success", "failure"}) {
                registry.counter("notifyhub.delivery.attempts", "channel", channel, "outcome", outcome);
                registry.timer("notifyhub.delivery.provider.duration", "channel", channel, "outcome", outcome);
            }
            for (String status : new String[]{"SENT", "FAILED"}) {
                registry.counter("notifyhub.delivery.completed", "channel", channel, "status", status);
            }
            registry.counter("notifyhub.delivery.retries", "channel", channel);
        }
    }

    public void attempt(String channel, String outcome, long nanos) {
        afterCommit(() -> {
            registry.counter("notifyhub.delivery.attempts", "channel", channel, "outcome", outcome).increment();
            registry.timer("notifyhub.delivery.provider.duration", "channel", channel, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        });
    }
    public void completed(String channel, String status) {
        afterCommit(() -> registry.counter("notifyhub.delivery.completed", "channel", channel, "status", status).increment());
    }
    public void retry(String channel) {
        afterCommit(() -> registry.counter("notifyhub.delivery.retries", "channel", channel).increment());
    }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Delivery metrics require a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
