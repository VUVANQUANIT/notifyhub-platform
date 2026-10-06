ALTER TABLE auth.users ADD COLUMN recovery_version bigint NOT NULL DEFAULT 0;
CREATE TABLE auth.recovery_challenges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    recovery_version bigint NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,user_id) REFERENCES auth.users(tenant_id,id)
);
CREATE INDEX recovery_user ON auth.recovery_challenges(tenant_id,user_id);
CREATE TABLE auth.recovery_mail_outbox (
    challenge_id uuid NOT NULL REFERENCES auth.recovery_challenges(id),
    kind varchar(12) NOT NULL CHECK (kind IN ('CODE','NOTICE')),
    encrypted_code text,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENT','FAILED','EXPIRED')),
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    failure_code varchar(40),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (challenge_id,kind),
    CHECK ((kind='CODE' AND state='PENDING' AND encrypted_code IS NOT NULL)
        OR ((kind='NOTICE' OR state<>'PENDING') AND encrypted_code IS NULL))
);
CREATE INDEX recovery_mail_pending ON auth.recovery_mail_outbox(available_at,challenge_id) WHERE state='PENDING';
