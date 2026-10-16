# Lease inspections, handovers and checkout

This workflow implements the documented move-in → occupation → move-out inspection → financial reconciliation → unit release journey. It uses the existing lease controllers, property authorization and organization delegation, private image storage, audit log, lease history, notifications, maintenance guard and billing/deposit clearance.

## Enable tracking deliberately

For a SIGNED or ACTIVE lease, a current owner or delegated agency OWNER/ADMIN may POST `{managerLease}/inspections/enable` with `requestId` and `leaseVersion`.

* On SIGNED leases, an accepted MOVE_IN report is required before activation; an accepted MOVE_OUT report is required before completion and release.
* On ACTIVE leases, only move-out is required. The system refuses to fabricate a retrospective move-in baseline.
* Tracking cannot be disabled. Enrollment is immutable and audited. Existing leases retain their earlier activation/checkout behavior until explicitly enrolled. New leases also require explicit enrollment; clients should enable it after signing when their business process requires inspections.
* Tracking never changes the signed lease, lease version, application, reservation or financial account. Inspection commands use their own versions and idempotency keys.

The tenant cannot enable tracking, prepare reports or attest on behalf of the manager. A manager account that is also this tenant cannot attest as the counterparty. A delegated agency can conduct an inspection; this does not confer ownership or financial deduction permissions.

`managerLease` = `/api/v1/organizations/{organizationId}/properties/{propertyId}/leases/{leaseId}`.

`tenantLease` = `/api/v1/me/leases/{leaseId}`.

## Prepare, share, review, resolve

1. Manager POSTs `{managerLease}/inspections` with `requestId`, `kind` (`MOVE_IN` or `MOVE_OUT`) and `details`. One report of each kind is allowed per lease.
2. Each report has immutable numbered revisions and its own optimistic `version`. PUT `{managerLease}/inspections/{inspectionId}` creates a new revision with `requestId`, `version` and complete `details`. Draft and disputed reports can be revised. Awaiting review and accepted reports cannot be edited.
3. Download the exact JSON document from `.../{inspectionId}/revisions/{revisionId}/document`, review its `documentHash`, then POST manager `.../{inspectionId}/share`. Sharing requires current version/revision/hash, password confirmation, `consent: true`, an explanation, and vacancy/handover confirmation for MOVE_OUT.
4. Tenant GETs the shared report and evidence. POST tenant `.../{inspectionId}/accept` uses the same exact revision/hash and password-confirmed consent. MOVE_OUT additionally requires `vacancyConfirmed: true`; acceptance acknowledges the recorded condition, meter readings, actual key counts, evidence and handover.
5. Tenant may POST `.../{inspectionId}/dispute` instead, with `requestId`, version, shared `revisionId`, reason and unique `itemKeys` from that report. An empty item list denotes a general dispute. A dispute preserves the reviewed revision and blocks completion; it never establishes liability or moves money.
6. Manager responds with a new revision and shares it again. The previous shared report and dispute remain visible. The tenant cannot accept a disputed revision without a new shared revision. Managers and platform administrators cannot force acceptance or clear a dispute.
7. ACCEPTED reports are final and database-protected against subsequent updates. Record later issues through maintenance or the relevant dispute process. A tenant's acceptance is an operational account/password attestation, not a claim of certified legal e-signature or independent inspection.

Review requests: `requestId`, `version`, `revisionId`, `documentHash`, `consent`, `vacancyConfirmed`, `password`, `reason`. Passwords are checked against the active account and excluded from operation fingerprints and persistence. Share/accept routes consume the existing password confirmation rate limit.

## Condition and handover details

`details` contains title, observedAt (UTC instant), summary, items, meters, keys and evidenceIds.

* 1–100 items: stable key, area, name, condition, quantity, notes and optional nonnegative `estimatedCharge` (two decimals). Conditions: NEW, GOOD, FAIR, POOR, DAMAGED, MISSING, NOT_INSPECTED. Stable keys allow comparison between inspections.
* Up to 20 meter readings: key, label, serialNumber, reading (up to three decimals) and unit. Different meter serials remain explicit; the server does not derive consumption or invent utility charges.
* 1–30 key inventory rows: key, label, expectedCount and transferredCount. Counts may record missing keys. Acceptance acknowledges actual counts; it does not silently assert that expected and returned counts match. Do not enter physical key codes or access passwords.
* Up to 30 selected evidence image IDs. IDs must be unique and belong to that report; cross-report or cross-lease evidence references are rejected.
* Observation times cannot precede lease creation or be in the future. MOVE_IN can be prepared/reviewed only while SIGNED. MOVE_OUT requires a SIGNED or ACTIVE lease, observation after any activation, and an observation date within seven days before the currently agreed end date or later. An accepted mutual termination supplies that end date. A report cannot be used to close a lease before the agreed end.

The currency is frozen from the signed lease revision. Estimated charges are observations only. They do **not** create invoices, deduct deposits, accept damage liability, authorize a refund, or change rent. Use the existing owner billing/charge and mutual deposit proposal/acceptance workflows for financial effects, with their independent permissions and version checks.

## Private photographic evidence

GET/POST `.../{inspectionId}/evidence`; GET `.../{inspectionId}/evidence/{imageId}/content`.

Upload multipart fields: file, requestId, version, caption. Photos are decoded and re-encoded through the existing image processor; original EXIF/GPS/text metadata, filenames, paths and trailing bytes are not retained. Each report permits at most 50 images. No storage key or uploader account ID is exposed. Committed evidence is append-only; transaction rollback removes newly written files.

Managers can upload only during draft/dispute editing. Their photos remain hidden from the tenant until selected in a shared revision. Publication is permanent for that lease's authorized parties even when a later revision omits a previously published photo; this preserves the evidence actually reviewed.

Tenants can upload counter-evidence while reviewing/disputing a shared report. Tenant evidence is visible to both parties immediately; it does not silently alter the manager's report. The tenant can dispute and ask for a revision incorporating it. Accepted reports cannot receive new evidence.

Every photo/document download rechecks current authorization and records an audit event. Access is limited to the lease tenant and current owner/delegated management administrators, including appropriate historical lease access after checkout. Former agencies lose access immediately; providers, unrelated tenants, guests and platform administrators receive no blanket inspection access. Responses use `Cache-Control: no-store`; binary responses use `nosniff` and safe generated filenames. Keep the configured image directory private and protect it with deployment filesystem permissions, encryption and backup controls, as for existing work evidence. No public static file mapping or signed public URL is added.

## Read APIs and client behavior

Both lease prefixes provide:

* GET `/inspections`: at most two reports; tenants see shared reports only.
* GET `/inspections/{inspectionId}`: current manager draft or last shared tenant revision. A private revised draft is never substituted into the tenant view. Report versions may advance as manager drafts or evidence change; reload before issuing commands.
* GET `/inspections/{inspectionId}/revisions`, `/history`, `/attestations`, `/evidence`: limit 1–100, offset nonnegative. Revisions newest first; history/evidence/attestations chronological. Tenant history excludes draft-only events. Attestations identify role, decision, reviewed hash, confirmation method and time without exposing account credentials or unnecessary identity data.
* GET `/inspections/{inspectionId}/revisions/{revisionId}/document`: exact immutable JSON, attachment, no-store, audited. Tenant cannot fetch an unshared draft by guessing its ID.
* GET `/inspections/comparison`: accepted move-in baseline against the current manager move-out draft or latest shared tenant move-out revision. Union of stable item keys shows additions/removals/changed observations. No baseline means no comparison; legacy enrollment must not invent one. Read the full reports for meter/key differences. Comparison does not calculate billable damages or adjudicate normal wear.
* GET `/checkout`: tracking flags, accepted inspection state, billing readiness, maintenance readiness, lease closed/released flags, readiness to release and explicit blockingSteps. States are an informational snapshot; the mutation endpoints recheck all guards while holding the property lock.

Do not infer tenant consent from a report upload, shared status, read, counter-evidence upload or elapsed time. Only the explicit acceptance establishes it. Render user-supplied report text as plain text.

## Completion and release

For enrolled leases, the existing `/activate`, `/complete` and `/release-unit` endpoints enforce inspection gates. Completion still requires the agreed end date and sets the unit UNAVAILABLE. A resolved inspection does not override open maintenance, outstanding balances, unverified payment reports, unfinished billing or unresolved deposit/refund settlement. `/release-unit` checks all existing guards plus inspection acceptance and requires explicit vacancy confirmation. Releasing the unit does not automatically publish a listing.

Checkout blocking steps: ACCEPT_MOVE_OUT_INSPECTION, CLOSE_LEASE_AT_AGREED_END, CLEAR_AND_FINALIZE_BILLING, RESOLVE_MAINTENANCE. `readyToRelease` is false for an already released unit. A signed lease that never became active can complete at its agreed end using an actual, bilaterally confirmed move-out/vacancy report. No retrospective move-in baseline is created. An accepted move-out handover prevents subsequent activation of that lease.

## Transactions, idempotency and auditing

Writes follow existing management organization → property locking; tenant commands lock the property and recheck the lease. Report versions serialize competing revisions and competing accept/dispute decisions. Operations use a lease-scoped requestId bound to actor, action, target and canonical payload. Exact retries return current authorized state without duplicate revisions, decisions, photos, events or notifications; a changed payload/actor/action/target returns 409. Password-confirmed retries must still authenticate.

Enrollment, report content, shared revisions, decisions, evidence metadata/publications, events and operation records are protected against mutation/deletion/truncation. Accepted report identities/state are protected. All state changes, audit evidence and applicable notifications commit together. Notifications use the existing lease inbox source and a generic preview, never report text, key counts, meter serials, dispute reasons or passwords. Draft-only activity is not sent to tenants. No email, SMS, external messaging, payment transfer, automatic adjudication or lease renewal is added.

Migration V14 adds the inspection domain without changing earlier migrations or financial records. No new external dependency or credential is required. Run `./scripts/test-foundation.ps1 -UseLocalDatabase` for the full PostgreSQL regression suite; tests use zaza_foundation_test, separate from application data.
