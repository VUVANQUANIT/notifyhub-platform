CREATE TABLE notification.result_outbox (
    event_id uuid PRIMARY KEY,
    notification_id uuid NOT NULL UNIQUE REFERENCES notification.deliveries(notification_id),
    campaign_id uuid NOT NULL,
    envelope jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(128)
);
CREATE INDEX result_outbox_pending ON notification.result_outbox(next_attempt_at, occurred_at)
    WHERE published_at IS NULL;

-- Existing terminal deliveries also need a result after upgrading a running installation.
INSERT INTO notification.result_outbox(event_id,notification_id,campaign_id,envelope,occurred_at)
SELECT event_id,notification_id,campaign_id,
    jsonb_build_object('eventId',event_id,'eventType',CASE WHEN status='SENT' THEN 'NotificationSent' ELSE 'NotificationFailed' END,
        'eventVersion',1,'tenantId',tenant_id,'campaignId',campaign_id,'occurredAt',completed_at,
        'correlationId',correlation_id,'payload',jsonb_build_object('notificationId',notification_id,
            'recipientId',recipient_id,'channel',channel,'status',status,'attempts',attempts,
            'providerReference',provider_reference,'failureCode',last_error)),completed_at
FROM (SELECT gen_random_uuid() AS event_id, d.* FROM notification.deliveries d WHERE status IN ('SENT','FAILED')) terminal;
