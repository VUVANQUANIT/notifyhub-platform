CREATE TABLE campaign.campaigns (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name text NOT NULL CHECK (length(trim(name)) > 0),
    channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    subject text,
    body text NOT NULL CHECK (length(trim(body)) > 0),
    scheduled_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','RUNNING','COMPLETED','FAILED')),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    create_key varchar(128) NOT NULL,
    create_fingerprint varchar(64) NOT NULL,
    start_key varchar(128),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, create_key),
    CHECK ((channel = 'EMAIL' AND subject IS NOT NULL AND length(trim(subject)) > 0)
        OR (channel = 'SMS' AND subject IS NULL)),
    CHECK (scheduled_at IS NULL OR scheduled_at >= created_at),
    CHECK ((status IN ('DRAFT','SCHEDULED') AND started_at IS NULL)
        OR (status IN ('RUNNING','COMPLETED','FAILED') AND started_at IS NOT NULL AND started_at >= created_at)),
    CHECK (status <> 'SCHEDULED' OR scheduled_at IS NOT NULL),
    CHECK (started_at IS NULL OR scheduled_at IS NULL OR started_at >= scheduled_at)
);
CREATE INDEX campaigns_tenant_created ON campaign.campaigns(tenant_id, created_at DESC, id);
CREATE INDEX campaigns_due ON campaign.campaigns(scheduled_at) WHERE status = 'SCHEDULED';

CREATE TABLE campaign.recipient_imports (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','COMPLETED','REJECTED')),
    total_rows bigint NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    valid_rows bigint NOT NULL DEFAULT 0 CHECK (valid_rows >= 0),
    duplicate_rows bigint NOT NULL DEFAULT 0 CHECK (duplicate_rows >= 0),
    invalid_rows bigint NOT NULL DEFAULT 0 CHECK (invalid_rows >= 0),
    errors jsonb NOT NULL DEFAULT '[]',
    rejection_reason text,
    UNIQUE (tenant_id, campaign_id, id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign.campaigns(tenant_id, id),
    CHECK (total_rows = valid_rows + duplicate_rows + invalid_rows),
    CHECK ((status IN ('PENDING','PROCESSING') AND completed_at IS NULL)
        OR (status IN ('COMPLETED','REJECTED') AND completed_at IS NOT NULL AND completed_at >= created_at)),
    CHECK (status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND length(trim(rejection_reason)) > 0))
);
CREATE UNIQUE INDEX one_processing_import ON campaign.recipient_imports(tenant_id, campaign_id)
    WHERE status = 'PROCESSING';

CREATE TABLE campaign.campaign_recipients (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    destination text NOT NULL,
    personalization jsonb NOT NULL,
    source_import_id uuid NOT NULL,
    UNIQUE (tenant_id, campaign_id, destination),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign.campaigns(tenant_id, id),
    FOREIGN KEY (tenant_id, campaign_id, source_import_id)
        REFERENCES campaign.recipient_imports(tenant_id, campaign_id, id)
);

CREATE TABLE campaign.outbox_events (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    event_version integer NOT NULL DEFAULT 1,
    occurred_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    published_at timestamptz,
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign.campaigns(tenant_id, id)
);
CREATE INDEX outbox_unpublished ON campaign.outbox_events(occurred_at) WHERE published_at IS NULL;
