ALTER TABLE notification.deliveries ADD COLUMN redrive_parent_id uuid REFERENCES notification.deliveries(notification_id);
CREATE TABLE notification.redrive_requests (
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    parent_id uuid NOT NULL UNIQUE REFERENCES notification.deliveries(notification_id),
    notification_id uuid NOT NULL UNIQUE REFERENCES notification.deliveries(notification_id),
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,request_id)
);
