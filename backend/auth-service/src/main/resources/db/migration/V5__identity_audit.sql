CREATE TABLE auth.audit_outbox (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES auth.tenants(id),
    envelope text NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_pending ON auth.audit_outbox(available_at) WHERE published_at IS NULL;
