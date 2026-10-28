ALTER TABLE app_users ADD COLUMN email_verified_at TIMESTAMPTZ, ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 CHECK(security_version>=0);
ALTER TABLE auth_sessions ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid(), ADD COLUMN mfa_verified_at TIMESTAMPTZ;
CREATE UNIQUE INDEX auth_sessions_public_id ON auth_sessions(id);
CREATE TABLE account_mfa (
 user_id UUID PRIMARY KEY REFERENCES app_users(id),factor_id UUID NOT NULL,
 encrypted_secret TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),last_counter BIGINT NOT NULL DEFAULT -1,
 pending_until TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE account_recovery_codes (
 user_id UUID NOT NULL REFERENCES app_users(id),code_hash CHAR(64) NOT NULL,
 consumed_at TIMESTAMPTZ,PRIMARY KEY(user_id,code_hash)
);
CREATE TABLE account_security_tokens (
 token_hash CHAR(64) PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),
 purpose VARCHAR(15) NOT NULL CHECK(purpose IN('VERIFY_EMAIL','RESET_PASSWORD')),
 expires_at TIMESTAMPTZ NOT NULL,created_at TIMESTAMPTZ NOT NULL,used_at TIMESTAMPTZ
);
CREATE INDEX account_security_tokens_user ON account_security_tokens(user_id,purpose,created_at DESC);
CREATE TABLE security_attempt_windows (
 subject CHAR(64) NOT NULL,purpose VARCHAR(30) NOT NULL,window_start BIGINT NOT NULL,
 attempts INTEGER NOT NULL CHECK(attempts>0),PRIMARY KEY(subject,purpose,window_start)
);
CREATE TABLE security_mail_outbox (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),purpose VARCHAR(30) NOT NULL,
 encrypted_message TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL,expires_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','SENT','FAILED','EXPIRED')),
 attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL,last_attempt_at TIMESTAMPTZ
);
CREATE INDEX security_mail_pending ON security_mail_outbox(status,next_attempt_at);
