CREATE TABLE reporting.identity_audit (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    action varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload_hash varchar(64) NOT NULL
);
CREATE INDEX audit_tenant_time ON reporting.identity_audit(tenant_id,occurred_at DESC,event_id);
