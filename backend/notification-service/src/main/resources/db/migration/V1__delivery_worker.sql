CREATE TABLE notification.deliveries (
    notification_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    channel varchar(8) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    payload_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RETRY_PENDING', 'SENT', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    next_attempt_at timestamptz,
    provider_reference text,
    last_error varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CHECK ((status IN ('SENT', 'FAILED')) = (completed_at IS NOT NULL)),
    CHECK ((status = 'RETRY_PENDING') = (next_attempt_at IS NOT NULL))
);
CREATE INDEX deliveries_campaign ON notification.deliveries(tenant_id, campaign_id, status);

-- Durable retry/DLQ handoff: commit before acknowledging the inbound RabbitMQ message.
CREATE TABLE notification.delivery_handoffs (
    id uuid PRIMARY KEY,
    dedupe_key varchar(128) NOT NULL UNIQUE,
    notification_id uuid REFERENCES notification.deliveries(notification_id),
    exchange_name varchar(128) NOT NULL,
    routing_key varchar(64) NOT NULL,
    payload bytea NOT NULL,
    attempt_number integer NOT NULL,
    failure_type varchar(128) NOT NULL,
    available_at timestamptz NOT NULL,
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    last_error varchar(128),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX handoffs_pending ON notification.delivery_handoffs(available_at, created_at)
    WHERE published_at IS NULL;
