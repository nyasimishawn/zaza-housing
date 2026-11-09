# Keycloak authentication

Zaza follows the backend-mediated pattern used in `horizonauth`: the backend exchanges
username/password and refresh grants, uses a confidential service account for identity
administration, and validates bearer JWTs against the realm's signing keys. Zaza uses its
own realm `zaza` and client `zaza-backend`; it does not use the Spiro realm or credentials.

## Start locally

From the backend project folder, with Docker Desktop running:

```powershell
.\scripts\start-keycloak.ps1 -EnableBackend
```

This creates random client and bootstrap administrator secrets in the ignored `.env`,
preserves existing secrets and encryption keys, enables Keycloak mode for the backend,
and runs the equivalent Docker command:

```powershell
docker compose --env-file .env -f compose.keycloak.yaml up -d
```

Wait for <http://localhost:9080/realms/zaza/.well-known/openid-configuration> to respond,
then restart the backend in Android Studio or run `scripts/start-local.ps1`.
Run one backend instance per port; the default is 8080.

The admin console is <http://localhost:9080/admin/>. Username: `zaza-admin`.
The generated password is `ZAZA_KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD` in the local `.env`.
Do not put the client secret, administrator password, or database credentials in Flutter.

The container binds only to 127.0.0.1:9080. Its development database persists in the
`zaza-auth_keycloak-data` Docker volume; it does not use or modify the application's
PostgreSQL database for Keycloak storage. `docker compose -f compose.keycloak.yaml stop`
stops Keycloak without removing identities. Startup import skips an existing realm;
changing the JSON or `.env` later does not rotate a client secret already stored there.
Update an existing client's secret explicitly in Keycloak and `.env` together.

## Configuration

| Variable | Value |
| --- | --- |
| `ZAZA_KEYCLOAK_ENABLED` | `true` to use Keycloak; `false` retains existing local authentication |
| `ZAZA_KEYCLOAK_URL` | `http://localhost:9080` locally; HTTPS required remotely |
| `ZAZA_KEYCLOAK_REALM` | `zaza` |
| `ZAZA_KEYCLOAK_CLIENT_ID` | `zaza-backend` |
| `ZAZA_KEYCLOAK_CLIENT_SECRET` | Generated locally; required when enabled |
| `ZAZA_KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD` | Container bootstrap only |

The existing security/document encryption key ring is required for encrypted refresh
credentials. The startup helper initializes it only if absent. Preserve these keys with
backups; do not regenerate them when restarting or moving the project.

The imported confidential client enables Direct Access Grants and service accounts,
disables browser/implicit grants, adds the API audience, and grants its service account
only `manage-users`, `view-users`, and `query-users` in `realm-management`. Zaza
administration routes remain protected by application roles, verified email and MFA.
No public Keycloak admin/token/role-assignment diagnostic endpoints are exposed.

## HTTP contract

Existing routes and account response fields remain available:

* `POST /api/v1/auth/register`: `{email,password,displayName}`. Creates a regular
  Keycloak identity and a linked Zaza account. No caller-supplied role is accepted.
* `POST /api/v1/auth/login`: `{email,password,code?,recoveryCode?}`. Returns
  `{accessToken,tokenType,expiresAt,user,refreshToken,refreshExpiresAt}`. In Keycloak
  mode, `accessToken` is a Keycloak JWT. Local mode returns null refresh fields.
* `POST /api/v1/auth/refresh`: `{refreshToken}`. Public but rate limited. Do not attach
  an expired Authorization header. Rotates access/refresh tokens and revokes the
  previous local access token. The stable local session ID is retained for registered
  push devices and security-session management. Refresh is available only in Keycloak mode.
* `POST /api/v1/auth/logout`: bearer access token, no body. Ends the provider session
  using the encrypted refresh credential and revokes the local access/refresh session.
* `GET /api/v1/me`: bearer token; returns the existing local account shape.

Use `api/keycloak-authentication.http` as a request guide. Credentials and tokens belong
in the HTTP client's private environment. JWT issuer, audience, authorized client,
signature, token type, expiry and local session are validated. The API accepts only
tokens from its own login/refresh workflow, not arbitrary realm tokens. Provider outages
return a safe error and never fall back to local password authentication.

Keycloak access tokens live for five minutes. The application also caps the access and
refresh session at `ZAZA_SESSION_DURATION` from the original login. Refresh cannot
extend that limit or the existing 15-minute admin MFA proof window. Session revocation
and local account disablement block both access and refresh immediately at this API.
Disabling an identity directly in Keycloak prevents login/refresh; an already issued
access token can remain usable until its short expiry unless its local session is
also revoked. This implementation does not register a back-channel logout endpoint.

## Existing accounts, permissions and MFA

`keycloak_identities` binds `(issuer, subject)` to an existing Zaza UUID. Organization
membership, ownership, audits and platform administration remain local and are checked
on each request. A Keycloak role/group cannot grant ownership or platform administration.

Existing accounts are **not automatically linked by matching email**. An operator must
independently verify the Keycloak identity and existing Zaza account before inserting the
binding. For a verified pair, use the local/operator database procedure:

```sql
INSERT INTO keycloak_identities(user_id, issuer, subject)
VALUES ('<verified-existing-zaza-user-uuid>',
        'http://localhost:9080/realms/zaza', '<verified-keycloak-user-id>');
```

Keep the existing Zaza user UUID and platform role; do not recreate organization members.
Record an audit event through the controlled operator process. First-time Keycloak users
without a colliding email can be provisioned as regular Zaza accounts on login. Accounts
created through the backend are linked during registration. Keycloak-managed users have
a random unusable local password hash; passwords are verified only by Keycloak when
this mode is enabled.

Existing Zaza authenticator enrollment, recovery codes, verified-email gates, security
mail outbox and password-confirmed business actions are retained. Password confirmation
checks the linked Keycloak subject. Password changes/recovery update Keycloak and revoke
local sessions. Retain Zaza's existing mail delivery setup for recovery/verification;
Keycloak's built-in reset-email screen is disabled in the development realm. Configure
MFA through Zaza's account-security routes; independently enabling a second Keycloak OTP
flow requires an explicit combined login contract and is outside this setup.

Flutter is unchanged on this branch apart from earlier dependency/tooling changes.
It still uses Firebase until a separately authorized frontend/API migration.

## Verify

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Tests target only the `zaza_foundation_test` schema. The default test profile uses local
authentication so the existing suite remains valid. `KeycloakAuthenticationTests` enables
Keycloak mode against a signed, HTTP OIDC test provider and real PostgreSQL. It covers
token rotation/revocation, invalid issuer/audience/client/signature/expiry, account
disablement, encrypted refresh storage, account-link collisions, password changes and
organization isolation without contacting a real identity server.

For production use a production Keycloak deployment with HTTPS and a supported external
database, secret management and backups. The supplied Compose file is for local
development. Direct password grants are the explicitly selected compatibility flow;
hosted login/PKCE and social login can be added as a separate frontend migration.

Protocol references: [Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers),
[realm import](https://www.keycloak.org/server/importExport),
[Keycloak containers](https://www.keycloak.org/server/containers),
[Spring Security JWT validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
