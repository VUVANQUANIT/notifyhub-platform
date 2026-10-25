package com.vuvanquan.notifyhub.notification.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"notification.events.enabled", "notification.events.polling-enabled"}, havingValue = "true")
public class ResultPoller {
    private final ResultPublisher publisher;
    public ResultPoller(ResultPublisher publisher) { this.publisher = publisher; }
    @Scheduled(fixedDelayString = "${notification.events.poll-delay-ms:1000}")
    public void publish() {
        for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) if (!publisher.publishOne()) break;
    }
}
