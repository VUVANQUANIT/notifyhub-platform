ALTER TABLE auth.users ADD COLUMN mfa_secret text;
ALTER TABLE auth.users ADD COLUMN mfa_pending_secret text;
ALTER TABLE auth.users ADD COLUMN mfa_pending_expires_at timestamptz;
ALTER TABLE auth.users ADD COLUMN mfa_last_step bigint NOT NULL DEFAULT -1;
CREATE TABLE auth.mfa_recovery_codes (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    code_hash varchar(64) PRIMARY KEY,
    FOREIGN KEY(tenant_id,user_id) REFERENCES auth.users(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE auth.refresh_tokens DROP CONSTRAINT refresh_tokens_session_id_fkey;
ALTER TABLE auth.refresh_tokens ADD FOREIGN KEY(session_id) REFERENCES auth.refresh_sessions(id) ON DELETE CASCADE;
ALTER TABLE auth.recovery_mail_outbox DROP CONSTRAINT recovery_mail_outbox_challenge_id_fkey;
ALTER TABLE auth.recovery_mail_outbox ADD FOREIGN KEY(challenge_id) REFERENCES auth.recovery_challenges(id) ON DELETE CASCADE;
ALTER TABLE auth.invitation_mail_outbox DROP CONSTRAINT invitation_mail_outbox_invitation_id_fkey;
ALTER TABLE auth.invitation_mail_outbox ADD FOREIGN KEY(invitation_id) REFERENCES auth.invitations(id) ON DELETE CASCADE;
