CREATE TABLE reporting.campaign_reports (
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    expected bigint CHECK (expected > 0),
    progress_event_id uuid,
    progress_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,campaign_id)
);
CREATE TABLE reporting.delivery_reports (
    notification_id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    channel varchar(8) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    status varchar(8) NOT NULL CHECK (status IN ('SENT','FAILED')),
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 10),
    provider_reference text,
    failure_code varchar(128),
    occurred_at timestamptz NOT NULL,
    envelope jsonb NOT NULL,
    UNIQUE (tenant_id,campaign_id,recipient_id),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES reporting.campaign_reports(tenant_id,campaign_id)
);
CREATE INDEX delivery_reports_campaign ON reporting.delivery_reports(tenant_id,campaign_id,status);
CREATE INDEX campaign_reports_updated ON reporting.campaign_reports(tenant_id,updated_at DESC,campaign_id);
CREATE TABLE reporting.progress_inbox (
    event_id uuid PRIMARY KEY,
    envelope jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
