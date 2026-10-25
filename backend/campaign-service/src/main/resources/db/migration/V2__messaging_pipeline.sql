ALTER TABLE campaign.outbox_events
    ADD COLUMN publish_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error text;

CREATE TABLE campaign.dispatch_jobs (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    last_recipient_id uuid,
    completed_at timestamptz,
    UNIQUE (tenant_id, campaign_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign.campaigns(tenant_id, id)
);
CREATE INDEX dispatch_pending ON campaign.dispatch_jobs(occurred_at) WHERE completed_at IS NULL;
CREATE INDEX recipients_dispatch_cursor ON campaign.campaign_recipients(tenant_id, campaign_id, id);

CREATE TABLE campaign.delivery_tasks (
    notification_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    recipient_id uuid NOT NULL REFERENCES campaign.campaign_recipients(id),
    routing_key varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    UNIQUE (tenant_id, campaign_id, recipient_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign.campaigns(tenant_id, id)
);
CREATE INDEX tasks_pending ON campaign.delivery_tasks(next_attempt_at, created_at) WHERE published_at IS NULL;
