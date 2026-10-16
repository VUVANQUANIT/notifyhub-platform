CREATE TABLE campaign.delivery_results (
    notification_id uuid PRIMARY KEY REFERENCES campaign.delivery_tasks(notification_id),
    event_id uuid NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    status varchar(8) NOT NULL CHECK (status IN ('SENT','FAILED')),
    envelope jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,campaign_id,recipient_id),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES campaign.campaigns(tenant_id,id)
);
CREATE INDEX delivery_results_campaign ON campaign.delivery_results(tenant_id,campaign_id,status);
