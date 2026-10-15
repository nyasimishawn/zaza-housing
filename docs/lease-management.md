# Lease management and account-consent signing

Branch: `feature/lease-management`, based on Develop after PRs #3 and #4 merged. Additions use the existing shared controller/service/repository/model/DTO layers. Existing application reservations, audit, authentication, rate limiting, notifications, and private response handling are extended.

## Run and try it

```powershell
.\scripts\start-local.ps1
```

Flyway applies V7. Use `api/lease-management.http` with separate tenant and owner/agency tokens and an APPROVED application. Keep password confirmation values in the HTTP client's private environment; do not commit them. All commands require the current lease version returned by GET. Dates are calendar dates evaluated using the backend's UTC clock.

The workflow is draft → send → both parties sign → activate → close at the agreed end date → vacancy clearance. An unsigned agreement can instead be recalled/revised, declined, or cancelled. The early-termination workflow requires consent from both agreement parties and preserves the original signed agreement.

## Agreement content and revisions

Create a lease from the current approved application version. Its tracked unit reservation must still be ACTIVE/RESERVED and have the expected unit version. One live lease hold is allowed per application/unit.

Provide a title, start/end dates, monthly rent, deposit, ISO currency, monthly due day (1–28), and the agreement text. Text must be 20–20,000 characters; monetary amounts are nonnegative with at most two decimal places. Start dates cannot be in the past when creating, revising, sending, or signing; end dates must follow the start. The agreement text is supplied by the business; the backend does not generate jurisdiction-specific contractual clauses or billing/proration rules.

Each draft edit appends a new immutable revision. Its generated UTF-8 agreement includes the lease ID, revision number, frozen owner business name, tenant name/contact, exact premises/unit details, dates, money fields, and supplied text. SHA-256 is calculated over the exact downloadable bytes. Income and employment screening information are excluded from the agreement.

GET a revision's `/document` to download those exact bytes as plain UTF-8 text. Content uses attachment disposition, no-store, and nosniff. PDF layout/export is future work. Revisions, shares, signatures, events, termination proposals, and termination consents are protected against history rewriting at the database level. Database owners still control the database; the digest is not an external trust certificate.

An initial draft is private to authorized business managers. Sending explicitly shares a revision with its tenant. A recall creates a fresh draft revision; earlier signatures remain with the earlier revision and are never copied. During recalled-draft editing, tenants see the previously shared document and shared history, while the new draft stays private. Resending requires fresh signatures on the new revision. Fully signed agreements cannot be edited, recalled, or unilaterally cancelled.

## Signing permissions and evidence

Authorized owner-organization OWNER/ADMIN members and currently delegated agency OWNER/ADMIN members can prepare, send, recall, and manage leases. Ordinary members and unrelated accounts cannot read agreements. Tenant routes only access that account's shared leases. Membership/delegation revocation affects subsequent requests.

Only the tenant signs the TENANT party. Only a current OWNER/ADMIN of the owning organization, distinct from the tenant, signs the LESSOR party. Agency management delegation does not grant owner-signing authority.

Signing requires current lease version, exact revision ID, exact documentHash, typed name, current account password, and `consent: true`. The tenant's typed name must match the agreement's tenant name; the owner representative's typed name must match that account's display name. Names are whitespace-normalized and compared case-insensitively. Either party can sign first. Stale versions, old revisions, mismatched digests, repeated party signatures, and using one account for both parties are rejected.

Password confirmation reuses backend authentication and shares its per-IP authentication attempt quota. Passwords are not saved in agreements, evidence, audits, notifications, or responses, and the consent object's string representation redacts its password. The method records `ACCOUNT_PASSWORD_CONSENT`, signer account ID/email, typed name, party, document digest, and server timestamp. No signing certificate, external provider, identity/KYC check, SMS OTP, or independent witness is configured.

Once both parties sign the same revision, the lease becomes SIGNED and its application becomes LEASED. GET the revision's `/receipt` for the immutable two-party execution evidence and digest; partial signatures do not produce an execution receipt. This is an account-consent record, without a claim of jurisdiction-specific legal enforceability.

## Inventory and lifecycle

| Status | Allowed operations |
| --- | --- |
| DRAFT | Revise, send, cancel |
| SENT | Tenant/owner sign, owner/agency recall/cancel, tenant decline |
| SIGNED | Activate during term, agree early termination, close at agreed/natural expiry |
| ACTIVE | Agree early termination, close at agreed/natural expiry |
| COMPLETED / TERMINATED | Review history/receipts; perform vacancy clearance |
| CANCELLED | Review shared historical material; create a replacement draft from the still-approved application |

Application withdrawal/cancellation and ordinary unit edits are blocked while a lease holds the unit. Cancelling an unsigned lease releases its lease hold but leaves the approved application reservation intact; withdraw/cancel the application separately to release that reservation, or create a replacement lease.

Activation requires both signatures, an active property/tenant account, the tracked reservation, a date within the agreed term, and `possessionConfirmed: true`. It changes the unit to OCCUPIED and consumes the application reservation. It records a business handover confirmation; payment verification is not implemented.

Closure requires the natural end date or a mutually accepted early end date to have arrived. It moves the unit to UNAVAILABLE and closes the application. A signed lease that never activated can also close at natural expiry or an agreed earlier date, preventing an indefinite reservation. Pending unaccepted termination proposals are cancelled when a lease closes naturally.

The unit remains held for checkout after closure. `release-unit` requires an authorized manager, the current lease/unit versions, `vacancyConfirmed: true`, and a note. Only then does the unit become AVAILABLE and its lease hold end. Closure/clearance remain possible for archived properties. Listings/media do not automatically republish; review and publish again with current inventory versions. Inspection, deposit reconciliation, payments/refunds, and financial checkout are not verified by this manual vacancy confirmation.

## Early termination

Either agreement party can preview a proposal containing the lease ID, original revision/digest, proposing party, desired end date, and reason. Proposed dates must be today or later and before the original end date; a signed lease can agree an end before move-in. Agency managers can view the workflow but cannot provide owner-party consent.

The requester reviews the proposal and submits its exact digest, typed name, password, and consent. The other party downloads the stored proposal and accepts with fresh password-confirmed consent to that same digest. The requester cannot accept their own proposal. A pending proposal can be rejected by the other party or cancelled by its original requester. A passed proposal date cannot be accepted; cancel/reissue it. Accepted proposals and consents cannot be rewritten or unilaterally revoked.

Acceptance updates the effective end date without immediately closing an ACTIVE/SIGNED lease or releasing inventory. Close it on/after that date, then confirm vacancy. Only one pending/accepted proposal exists at a time; rejected/cancelled proposals remain in history.

## APIs and dashboards

Tenant base: `/api/v1/me/leases`. Manager base: `/api/v1/organizations/{organizationId}/properties/{propertyId}/leases`. Both require bearer authentication.

Both support GET list, `/summary`, `/{id}`, `/{id}/history`, `/{id}/revisions`, `/{id}/revisions/{revisionId}/document`, and `/{id}/revisions/{revisionId}/receipt`. Lists accept status, limit 1–100 (default 50), and nonnegative offset. Tenant revision lists only contain shared revisions. Dashboards count statuses, agreements awaiting both signatures (SENT), and closed leases awaiting checkout.

Manager commands: POST base to create; PUT `/{id}` to revise; POST `/{id}/send`, `/recall`, `/cancel`, `/sign`, `/activate`, `/complete`, `/release-unit`. Tenant commands: POST `/{id}/sign` and `/decline`.

Both parties use `/{id}/terminations/preview` and `/{id}/terminations` (POST), `/{id}/terminations` (GET), `/{id}/terminations/{terminationId}/document` (GET), and `/{id}/terminations/{terminationId}/accept`, `/reject`, `/cancel` (POST).

Existing in-app notifications now include optional leaseId and leaseUrl; prior application fields/type strings remain compatible. Owner-side actions notify the tenant; tenant-side actions notify current owner/agency administrators with a scoped manager URL. Historical inbox messages remain personal records; document access always checks current permissions. Events, evidence, inventory changes, application transitions, audits, and notifications commit together or roll back together.

## Verification and remaining work

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Tests use the separate `zaza_foundation_test` schema, real HTTP requests, and PostgreSQL. Coverage includes exact bytes/digests, privacy, signing order/consent/password/name checks, recall and stale evidence, concurrency, agency/owner permissions, application holds, activation, natural/early closure, vacancy release, history protection, and rollback on audit/notification failures. A test-only controllable clock verifies future end dates and expiry without changing the system clock.

Flutter lease screens, PDF export, external e-sign providers and provider verification, KYC/MFA, renewal/amendment proposals for executed agreements, rent schedules, billing/payment ledgers, financial move-out reconciliation, retention policies, and external notifications remain future work.
