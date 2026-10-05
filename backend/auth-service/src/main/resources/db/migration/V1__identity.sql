CREATE TABLE auth.tenants (
    id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    name varchar(100) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auth.users (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES auth.tenants(id),
    email varchar(254) NOT NULL CHECK (email=lower(email)),
    display_name varchar(100) NOT NULL,
    password_hash varchar(256) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,email)
);
CREATE INDEX users_tenant_created ON auth.users(tenant_id,created_at,id);
CREATE TABLE auth.refresh_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,user_id) REFERENCES auth.users(tenant_id,id)
);
CREATE INDEX sessions_user ON auth.refresh_sessions(tenant_id,user_id) WHERE revoked_at IS NULL;
CREATE TABLE auth.refresh_tokens (
    token_hash varchar(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES auth.refresh_sessions(id),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_session ON auth.refresh_tokens(session_id);
-- Keep consumed hashes until the session expires, so old-token reuse can revoke the family.
