# Administration, trust and support

This release adds account security, independent operational review and a private support/dispute workflow. It retains the existing inventory, agency, caretaker, billing and communication services.

## Available workflows

- Email verification, email password recovery, password change, session inventory/revocation, authenticator enrollment and sign-in, one-time recovery codes and recent MFA verification.
- An operational dashboard, searchable account directory, independent restriction/reactivation proposals, listing review and enforcement, audit metadata, versioned help drafts/publication, and bounded support response targets.
- Personal support requests, disputes and public-listing reports; redacted operator queues; independent claims, handoffs, priority/escalation, requests for information, resolution, reporter reopening within 14 days and final closure.
- Shared replies and operator-only notes; normalized JPEG/PNG evidence encrypted at rest, scoped downloads and append-only history; personal in-app support updates.

## Deployment configuration

Flyway migrations V23–V25 add security, administration/support and help-content tables. Apply these migrations with the backend rollout; they do not create privileged accounts. Back up the database and encrypted document directory using the existing deployment process.

Configure the existing `ZAZA_DOCUMENT_KEYS` key ring (`key-id:base64-encoded-32-byte-key`, comma-separated for retained keys) and `ZAZA_DOCUMENT_ACTIVE_KEY_ID`. Security encryption uses the existing key ring by default, with separate authenticated encryption contexts for MFA secrets, mail and case evidence. Optional `zaza.security.keys` / `zaza.security.active-key-id` properties can use a separate key ring. Retain old keys while encrypted content references them. Never commit key material or use the test fixture key outside tests. Evidence uses `ZAZA_DOCUMENT_DIRECTORY`.

Account email delivery defaults to `ZAZA_SECURITY_MAIL_MODE=off`. Password recovery and email delivery return a controlled unavailable response until encryption and delivery are configured. Choose:

- `smtp`: set `ZAZA_SMTP_HOST`, `ZAZA_SMTP_PORT` (default 587), optional `ZAZA_SMTP_USER` / `ZAZA_SMTP_PASSWORD`, and `ZAZA_SECURITY_MAIL_FROM`. The sender requires STARTTLS with hostname verification and bounded timeouts. The durable encrypted outbox retries failures with backoff, expires tokens and stops after eight failed delivery attempts. This is at-least-once delivery; a process failure around the delivery commit can produce a duplicate email containing the same one-time token.
- `local`: explicit development-only opt-in. Messages are written to `ZAZA_SECURITY_LOCAL_MAIL_DIRECTORY` (default `.local-security-mail`), including plaintext one-time tokens. Restrict access and remove expired development messages. This directory is ignored by Git. No local spool is enabled automatically.

Before enabling administration for an existing operator, verify the account email, enroll an authenticator and save the recovery codes. Production protects every `/api/v1/admin/**` route with a verified email, enabled MFA and an MFA check on the current bearer within 15 minutes. Legacy domain tests explicitly disable this outer gate; the new administration integration tests enable the production gate. New administration services independently enforce it regardless of the compatibility flag. Platform roles continue to use the existing controlled provisioning process; this feature provides no public privilege-grant endpoint or automatic administrator.

## API boundaries

| Prefix | Purpose |
| --- | --- |
| `/api/v1/me/security` | Personal security state and explicit security commands |
| `/api/v1/auth/recovery/request`, `/recovery/reset`, `/email/verify` | Public one-time token flows; recovery responses do not disclose account existence |
| `/api/v1/admin/operations` | Dashboard, accounts, account decisions, listing sources/reviews, help content, audit and safe settings |
| `/api/v1/me/support/cases` | Reporter-owned cases, messages, evidence, history and resolution acceptance/reopening |
| `/api/v1/admin/operations/support-cases` | Independent operator queue and case handling |
| `/api/v1/me/support/notifications` | Current user's support updates and read acknowledgements |
| `/api/v1/support/topics` | Published plain-text help topics, readable by guests |

Operations use UUID request identifiers and exact record versions. An exact retry reuses the same request ID and body. Changed bodies, stale versions and different actors/targets are rejected. Password confirmation is excluded from stored request fingerprints. Account restriction and reactivation require a different active operator's decision; self and platform-operator restrictions require the controlled operator process. Restriction revokes all sessions. Reactivation requires fresh sign-in.

Listing moderation stores a reviewed public snapshot and source version. Related property organization members cannot decide reviews. Suspended/rejected listings are excluded by the shared public-visibility policy used by discovery, media, enquiries and application creation; republishing does not remove that block. Approval must match a currently published snapshot and removes only the moderation block. A new report reopens a previously approved review without clearing an existing suspension/rejection.

Unassigned operator queues hide case subject, original description, reporter and listing identifiers. Only the assigned independent operator can read case messages and evidence. Former assignees lose those rights. Reporters never receive internal messages/evidence/history. Shared support notifications contain generic status text, not case evidence. Images are limited to 20 per case and 10 MB per upload, normalized before encryption, bound to case/image identifiers and checked against their stored hash on download.

## Deliberate scope limits

Support resolutions record an operational response; they do not execute refunds, rewrite leases or adjudicate ownership. Runtime settings are limited to first-response and resolution hours; existing case deadlines are preserved. Categories are validated fixed choices. Broad feature flags, arbitrary platform-role grants, production monitoring/backup infrastructure, approved legal policies, live provider payment integrations and multilingual help authoring remain separate work. Seeded FAQ text is informational, not a legal policy.

## Verification

`AdministrationSecurityTests`, `AdministrationOperationsTests` and `AdministrationSupportTests` contain 26 integration checks with production MFA enabled. They cover factor/recovery replay, durable throttling, session revocation, independent account approval, publication/version checks, help drafts, support privacy/handoffs, internal evidence, idempotency and append-only history. Run the complete repository foundation command after API/wiring changes:

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Use only a disposable `zaza_foundation_test` database/schema with test credentials and a configured FFmpeg/ffprobe runtime. Do not run the tests against the demo or production database.
