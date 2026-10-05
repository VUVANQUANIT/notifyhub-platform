package com.vuvanquan.notifyhub.campaign.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"campaign.messaging.enabled", "campaign.messaging.polling-enabled"},
        havingValue = "true")
public class MessagingPoller {
    private final OutboxPublisher outbox;
    private final CampaignDispatcher dispatcher;
    private final DeliveryTaskPublisher tasks;

    public MessagingPoller(OutboxPublisher outbox, CampaignDispatcher dispatcher, DeliveryTaskPublisher tasks) {
        this.outbox = outbox; this.dispatcher = dispatcher; this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${campaign.messaging.poll-delay-ms:1000}")
    public void publishEvents() {
        for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) if (!outbox.publishOne()) break;
    }

    @Scheduled(fixedDelayString = "${campaign.messaging.poll-delay-ms:1000}")
    public void dispatch() { dispatcher.dispatchOneBatch(); }

    @Scheduled(fixedDelayString = "${campaign.messaging.poll-delay-ms:1000}")
    public void publishTasks() {
        for (int i = 0; i < 100 && !Thread.currentThread().isInterrupted(); i++) if (!tasks.publishOne()) break;
    }
}
