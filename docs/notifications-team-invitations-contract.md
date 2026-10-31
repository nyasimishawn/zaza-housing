# Notifications and team invitations

This extends the existing REST, account-security email, organization-membership and in-app notification contracts. Flutter remains the client. Migration V29 is additive; no historical alert is sent externally as a backfill.

## Team invitation lifecycle

Owners and administrators invite an email address even before its account exists. Only owners may invite ADMIN; OWNER is never assignable. A verified inviter is required. Creating an invitation does not create a membership. The invited account must sign in, verify that same email using existing Account security, review the business and role, and explicitly accept.

Recipient lists, code previews, details and history require that email verification too. An unverified registration cannot inspect invitations addressed to someone else's email.

Pending invitations last seven days. Resending rotates the 256-bit code, invalidates the old code, increments the version, and sets a fresh seven-day expiry. Resends require a 60-second interval and are limited to five total sends. Each business may create 50 invitations in a rolling day. A business/email pair has one pending invitation. Expired rows remain in history; a new request expires an old pending row before replacing it.

Create requests carry a UUID requestId. Repeating it with the same email and role returns the same invitation; changing its payload conflicts. Commands carry the exact current version. Business locks serialize membership changes and acceptance. Acceptance rechecks the original inviter's current permissions, the recipient's verified email, expiry, status and existing membership. A cancelled, declined, expired or consumed invitation cannot grant access. Existing members must use the existing role management flow.

Raw codes are never returned to managers or written to logs. Only their SHA-256 hash is stored in the invitation; email text containing a code is encrypted using the existing SecurityCryptography key ring and a delivery-specific context. Invitation history is append-only. Codes are entered in the app rather than URL query strings.

| Route | Behavior |
| --- | --- |
| GET/POST /api/v1/organizations/{org}/invitations | Manager history / create |
| GET /api/v1/organizations/{org}/invitations/{id} | Current manager projection |
| POST .../{id}/resend or /cancel | Versioned manager command |
| GET .../{id}/history | Paginated safe activity |
| GET /api/v1/me/team-invitations | Invitations matching signed-in email |
| GET /api/v1/me/team-invitations/{id} | Current recipient projection |
| POST .../{id}/accept or /decline | Versioned recipient decision |
| POST /api/v1/me/team-invitations/preview | Code lookup; no membership change |
| POST /api/v1/me/team-invitations/redeem | Verified matching-email acceptance by code |

List endpoints accept limit (1–100), offset and optional status. History exposes actions and timestamps without inviter account identifiers. The existing immediate-add registered-member API remains supported and is separately labelled in Flutter.

## Notification inbox and preferences

The existing /api/v1/me/notifications list and summary remain available. Additive category and archived query filters, category/archivedAt/workspaceEventId response fields, bounded read-all/archive-all commands with a before timestamp, and individual archive/restore commands support the inbox.

Eleven categories cover teams, messages/viewings, rental applications/leases/inspections, billing, maintenance/caretaker operations, services, documents/ownership, support, discovery and personal reminders. Defaults are in-app on, external channels off. Muting in-app archives new items; they remain accessible in Archive. A settings update supplies every category exactly once and the current version.

PUT/GET /api/v1/me/notifications/settings supports IANA timezone, optional minute-of-day quietStart/quietEnd and INSTANT/DAILY email mode. External operational delivery respects quiet hours, including overnight ranges and timezone daylight-saving rules. Daily mode coalesces email into one account envelope for each local activity date, eligible at 08:00 the following morning. This digest is a private activity prompt; it does not expose message contents, amounts or addresses. It is not a marketing newsletter.

Direct invitation email and phone verification codes are separate from operational preferences and quiet hours. Existing password recovery and email verification continue to use SecurityMailService independently.

An AFTER INSERT database trigger captures all newly inserted in_app_notifications and creates channel deliveries in the same transaction. Support updates and saved-search matches bridge into that stream from their existing dedicated tables. No external delivery happens inside the original domain command. Existing notification routes still enforce current domain permissions when opened.

## Phone, device and reminder contracts

- POST /phone/challenge, POST /phone/verify, DELETE /phone under the notification base: verified email required before requesting SMS; E.164 numbers; encrypted number; only masked suffix returned. Six-digit code, ten-minute expiry, five guesses, one request per minute and five per rolling day. Codes are single use. Removing/changing a number disables its previous verification; removal disables SMS preferences and cancels queued SMS.
- GET/POST /devices and DELETE /devices/{id}: Android/iOS FCM token registration with installation UUID and label. Tokens are encrypted and hashed; never returned. Ten active devices per account. Tokens rebind to one account without exposing their old owner or deleting delivery history. Each registration binds to the authenticated server session; revoked/expired sessions cannot receive pending push.
- GET/POST /reminders, GET/PUT /reminders/{id}, POST /reminders/{id}/cancel: personal, versioned reminders with idempotent create IDs. Fifty active reminders, dates within 180 days, one-off or every 24 hours/seven days. Repeating reminders stop after their 180-day lifetime. Missed occurrences coalesce into one current reminder and advance to the next future time; they do not flood an inbox after downtime. The scheduler uses row locks, SKIP LOCKED and unique event deduplication.
- GET /deliveries, GET /deliveries/{id}/attempts, POST /deliveries/{id}/retry: account-scoped status, safe error codes and append-only attempts. Retry is bounded to eight total attempts, has a 60-second cooldown, and is unavailable for consumed/expired/cancelled deliveries and phone codes.
- GET /api/v1/admin/operations/notification-deliveries/summary: aggregate channel/status counts and provider readiness. Existing platform administrator and recent MFA gates apply. No addresses, bodies, codes or tokens are exposed.

## Provider configuration

All three operational channels default to off. Configuration is supplied through normal Spring properties or the deployment's secret manager. Never commit credentials, a service-account key, local delivery files or .env. SecurityCryptography uses the existing zaza.security.keys / active-key-id settings (and existing document-key fallback).

| Property | Values / purpose |
| --- | --- |
| zaza.notifications.email-mode | off, local, smtp |
| zaza.notifications.sms-mode | off, local, twilio |
| zaza.notifications.push-mode | off, local, fcm |
| zaza.notifications.local-directory | Default .local-notification-deliveries; private local demo/test files only |
| zaza.notifications.smtp-host / smtp-port / smtp-user / smtp-password / mail-from | SMTP; defaults reuse zaza.security SMTP settings |
| zaza.notifications.twilio-account-sid / twilio-auth-token / sms-from | Twilio SMS sender |
| zaza.notifications.fcm-project-id | Firebase project using Application Default Credentials |
| zaza.notifications.poll-ms / reminder-poll-ms | Default 30000 ms; bounded batches |

Local mode writes a stable UUID file once, containing its synthetic/local recipient and body. It never contacts an external provider. Keep the directory private and outside public/static roots. To demonstrate a new invitation locally, enable email-mode=local and account-security mail-mode=local with the existing encryption key configured, create a synthetic invitation, and use the local email token through the existing account verification flow. Do not use real recipients during automated tests.

SMTP requires STARTTLS, certificate/hostname verification and bounded timeouts. Twilio uses its fixed HTTPS Messages API. FCM uses its fixed HTTPS v1 API with Google Application Default Credentials and the firebase.messaging OAuth scope; use workload identity where available. Readiness for FCM means mode/project configured; credential validity is checked on send. No provider accounts or paid services are provisioned by this feature.

The dispatcher rechecks account state, preferences, verified destination, device session and invitation state before sending. Unconfigured channels or missing encryption are deferred without spending provider attempts. Transient failures use exponential backoff, becoming FAILED after five automatic attempts. External operations are at least once: a crash or ambiguous network result after provider acceptance can produce a duplicate. ACCEPTED means accepted by the local sink or provider, not proof of handset delivery/read. Provider delivery callbacks, bounce reconciliation, unsubscribe marketing campaigns and richer digest content are separate extensions.

## Validation

Run .\\scripts\\test-foundation.ps1 -UseLocalDatabase against the dedicated test schema, or with a disposable PostgreSQL cluster and explicit ZAZA_TEST_DB_* variables. Automated checks use generated example.test accounts and local delivery modes. They do not contact paid providers or modify demo/tenant records. Live SMTP/Twilio/FCM credentials and signed iOS builds require a configured environment before rollout.

Official protocol references: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Google Application Default Credentials](https://docs.cloud.google.com/java/getting-started/getting-started-with-google-auth-library), [Twilio Messages](https://www.twilio.com/docs/messaging/api/message-resource).
