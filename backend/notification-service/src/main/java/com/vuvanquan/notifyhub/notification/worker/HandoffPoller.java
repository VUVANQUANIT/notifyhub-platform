package com.vuvanquan.notifyhub.notification.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"notification.worker.enabled", "notification.worker.polling-enabled"}, havingValue = "true")
public class HandoffPoller {
    private final HandoffPublisher publisher;
    public HandoffPoller(HandoffPublisher publisher) { this.publisher = publisher; }
    @Scheduled(fixedDelayString = "${notification.worker.poll-delay-ms:1000}")
    public void publish() {
        for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) {
            if (!publisher.publishOne()) break;
        }
    }
}
