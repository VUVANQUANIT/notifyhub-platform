package com.vuvanquan.notifyhub.notification.worker;

public interface SmsDeliveryProvider {
    /** Returns provider acceptance reference, not a handset delivery receipt. */
    String send(SendNotificationTask task);
}
