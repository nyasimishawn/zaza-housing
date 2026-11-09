-- Identity binding is explicit. Never link an existing account by a matching email claim.
CREATE TABLE keycloak_identities (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);

-- Deleting a local session also revokes the ability to refresh it through this API.
-- Refresh credentials are authenticated-encrypted using the existing security key ring.
CREATE TABLE keycloak_sessions (
    token_hash CHAR(64) PRIMARY KEY REFERENCES auth_sessions(token_hash) ON DELETE CASCADE,
    refresh_hash CHAR(64) NOT NULL UNIQUE,
    encrypted_refresh_token TEXT NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    session_expires_at TIMESTAMPTZ NOT NULL
);
