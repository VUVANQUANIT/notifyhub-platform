package com.vuvanquan.notifyhub.notification.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class SimulatedSmsProvider {
    /** Deterministic local adapter: no external SMS request or recipient data in logs. */
    public String send(SendNotificationTask task) {
        return "sms-simulated:" + task.notificationId();
    }
}
