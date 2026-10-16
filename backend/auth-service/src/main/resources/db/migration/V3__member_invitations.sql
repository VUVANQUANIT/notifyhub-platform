ALTER TABLE auth.users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE auth.invitations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES auth.tenants(id),
    email varchar(254) NOT NULL,
    display_name varchar(100) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invitations_tenant ON auth.invitations(tenant_id,created_at);
CREATE UNIQUE INDEX invitations_active_email ON auth.invitations(tenant_id,email) WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE TABLE auth.invitation_mail_outbox (
    invitation_id uuid PRIMARY KEY REFERENCES auth.invitations(id),
    encrypted_token text,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENT','FAILED','EXPIRED')),
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state='PENDING') = (encrypted_token IS NOT NULL))
);
