# Encrypted document vault and manual verification

This adds the document and identity/business verification workflows described in the architecture request. The existing application controllers now support real, version-specific screening documents alongside the earlier profile document references. New controllers use the shared controller/service/repository/model/DTO layers. No external identity service, storage service or Java dependency is required.

## Supported content and storage

The vault accepts **JPEG/PNG scans**, up to 10 MB, through the existing bounded image decoder. Each document represents one scanned page; front/back or multi-page evidence should use separate documents in a submission. PDF, Office files, archives, SVG and arbitrary binaries are rejected. PDF sanitization/scanning and object-storage adapters remain future extensions; no claim of antivirus scanning is made.

Images are decoded and re-encoded, with original filenames, EXIF/GPS/text metadata and trailing bytes discarded. Private processing uses memory-backed image input/output. Vault multipart requests must declare Content-Length and be at most 11 MB; unknown-length requests receive 411 and oversized requests 413 before multipart parsing. The multipart memory threshold is 12 MB; unsafe lower overrides make vault uploads fail closed. At most two vault uploads enter processing concurrently per application instance; extra requests receive 429 with Retry-After. Other media routes retain their previous file-size limits.

Only encrypted bytes reach the vault storage adapter, including its atomic temporary files. AES-256-GCM uses a fresh random 12-byte nonce and a 128-bit authentication tag for every encryption. Authenticated context binds document ID, version ID, key ID, content type, plaintext digest and length. Downloads verify authentication, length and SHA-256 before returning bytes. Tampered/swapped ciphertext or unavailable keys fail closed. Content is returned as a private attachment with no-store, nosniff, a generated filename and a sandbox content policy.

The encrypted storage directory defaults to `.local-documents`, is ignored by Git, and has no public/static file mapping. Storage keys, encryption key identifiers/material, original filenames and owner account IDs are absent from ordinary document DTOs. The application must still run with deployment filesystem permissions, TLS, protected backups and an appropriate production secret manager; vault encryption does not protect a compromised application process or replace those deployment controls.

## Configure keys locally

Run `./scripts/initialize-document-key.ps1` once from the backend checkout. It generates 256-bit key material into the ignored `.env`, preserves unrelated environment settings, initializes blank placeholders, and preserves existing keys on rerun. It never prints key material. An incomplete nonempty configuration is rejected rather than overwritten.

Settings:

| Setting | Meaning |
| --- | --- |
| ZAZA_DOCUMENT_KEYS | Comma-separated `key-id:base64-256-bit-key` key ring |
| ZAZA_DOCUMENT_ACTIVE_KEY_ID | Key used for new versions |
| ZAZA_DOCUMENT_DIRECTORY | Private ciphertext directory |
| ZAZA_DOCUMENT_UPLOADS_PER_HOUR | Per-person/per-organization upload quota, default 20 |

Back up the keys with the corresponding encrypted-file backups, outside Git. For rotation, add a new key ID/material to the ring and select it as active; keep old read keys available. Existing encrypted versions are not rewritten. Reusing an existing ID with different material makes those files unreadable. With no keys configured, the rest of the application can start, but vault uploads/decryption are unavailable; it never falls back to plaintext. Malformed configured key material prevents startup.

## Ownership, versions and retention

Personal prefix: `/api/v1/me/documents`.

Organization prefix: `/api/v1/organizations/{organizationId}/documents`.

Personal documents belong to the authenticated person. Organization documents belong to the organization and require current OWNER/ADMIN membership. MEMBER, unrelated users and platform administrators have no blanket access. Membership removal takes effect on subsequent requests; the uploader does not retain personal access to organization documents after leaving.

* GET `/storage-status`: configuration availability and supported content/encryption; no secrets.
* POST prefix: multipart `metadata` JSON plus `file`. Metadata: requestId, version=0, category, title, retainUntil. Returns 201. Categories: IDENTITY, BUSINESS_REGISTRATION, PROPERTY_OWNERSHIP, LEASE, RECEIPT, INVOICE, INSPECTION, SERVICE, OTHER.
* GET prefix, GET `/{id}`, GET `/{id}/versions`, GET `/{id}/history`: private metadata with bounded pagination; lists default to 50 and accept limit 1–100 and nonnegative offset. Owner document list reads batch current-version metadata.
* POST `/{id}/versions`: same multipart fields with the current document version and unchanged category/title/retention. Creates an immutable replacement version; earlier versions remain independently addressable. A document permits at most 20 versions; an owner can hold at most 100 unpurged documents.
* GET `/{id}/versions/{versionId}/content`: owner-authorized, integrity-checked and audited download.
* POST `/{id}/retention`: requestId, version, retainUntil, reason. Retention is initially between one day and ten years and may only be extended. It is a minimum deletion deadline, not an automatic access-expiry date or a legal retention determination.
* POST `/{id}/archive` or `/restore`: requestId, version, reason. Archived documents cannot be downloaded or shared. Archive permanently revokes existing application grants; restoration does not resurrect earlier consent.

Open verification drafts/submissions and currently valid verification decisions hold their referenced evidence. Such evidence cannot be archived/purged until the case is withdrawn, expires or is otherwise resolved without a valid approval. Replacing the current vault version does not alter the frozen version reviewed in an application or verification case.

## Explicit application screening shares

An applicant may POST `/api/v1/me/applications/{applicationId}/documents` with requestId, current applicationVersion, documentId, documentVersionId, expiresAt and consent=true. The applicant must own the personal document and application. The application must be submitted and in an active review/approved/leased state; draft, withdrawn, rejected, cancelled and closed applications cannot grant reviewer access.

The share freezes exactly one immutable version for APPLICATION_SCREENING, with expiry within the next 30 days and no later than its retention deadline. A current unrevoked/unexpired grant cannot be silently extended; revoke it and give fresh consent. Expired grants may be replaced by fresh explicit consent. Each application records at most 100 grants.

Both applicant and current property managers can GET their existing application prefix plus `/{applicationId}/documents`, and GET `.../documents/{grantId}/content`. Manager prefix: `/api/v1/organizations/{organizationId}/properties/{propertyId}/applications`. Only current owner/delegated agency OWNER/ADMIN reviewers receive access. The manager cannot use the applicant's ordinary vault endpoint. Removing delegation, withdrawing/closing the application, grant expiry/revocation, or archiving/purging the source immediately prevents subsequent shared downloads. Receiver lists omit inaccessible grants.

Applicant POST `.../documents/{grantId}/revoke` takes requestId, current grant version and reason. Grant access can be revoked without deleting the immutable underlying document or rewriting the earlier consent record. Existing legacy profile document references remain references; they are not silently converted to uploads or implicitly shared.

## Personal identity and business verification

Personal prefix: `/api/v1/me/verifications`; kind IDENTITY.

Organization prefix: `/api/v1/organizations/{organizationId}/verifications`; kind BUSINESS.

1. POST prefix with requestId and details: claimedName, ISO countryCode, statement (20–2000 characters), and 1–10 unique documentVersionIds.
2. Evidence must belong to that exact subject and be ACTIVE with future retention. IDENTITY accepts IDENTITY scans; BUSINESS accepts BUSINESS_REGISTRATION scans. Business claimedName must match the organization's current name. Personal claimedName is a declaration reviewed by the operator, not a claim that a nickname/profile field was legally verified.
3. Drafts are private to the owner/current organization administrators. PUT `/{caseId}` with requestId, current case version and complete details creates a new immutable draft submission. Each case permits 20 submissions and one active case per subject.
4. POST `/{caseId}/submit` with requestId, version, current submissionId, consent=true and password. This freezes the exact evidence bundle and account/password consent for independent manual review. Uploading a later vault version does not replace it.
5. GET `/{caseId}`, `/submissions`, `/history` and GET prefix `/status` show owner progress and the effective verification summary.
6. POST `/{caseId}/withdraw` with requestId, version and reason closes a draft/submitted/reviewing/verified case, removes reviewer access and removes any current verified status. Evidence retention deadlines remain in force.

## Assigned platform review

Admin prefix: `/api/v1/admin/verifications`. PLATFORM_ADMIN status alone grants metadata queue access, not document access. The queue exposes case ID, kind, effective status, version, assignment-to-me flag and timestamps; it excludes names, subject IDs, documents and statements. Unsubmitted drafts, including withdrawn drafts, do not enter the queue.

* GET prefix: optional status/assignedToMe filters and bounded pagination.
* POST `/{caseId}/claim`: requestId, case version, password and reason. Only an independent operator can claim SUBMITTED work. Personal subjects, current organization members of any role, case creators and historical submission preparers cannot review that case.
* Only the currently assigned independent operator in UNDER_REVIEW can GET `/{caseId}`, `/history`, `/evidence`, or `/evidence/{documentVersionId}/content`. Access is restricted to the currently shared frozen bundle. Private GETs and file downloads are audited; unrelated vault documents and older/unsubmitted replacement evidence remain inaccessible.
* POST `/{caseId}/release`: same password-confirmed command returns work to SUBMITTED and revokes evidence access. A reviewer who becomes related to the subject can still release the case but cannot continue reviewing/deciding. Another independent operator must claim it.
* POST `/{caseId}/decide`: requestId, version, decision (VERIFY, REQUEST_CHANGES, REJECT), checksConfirmed, optional validUntil, password and reason (20–2000 characters). VERIFY requires confirmation of completed manual checks, validity strictly in the next 365 days and evidence retained for that entire period. Business name is rechecked. Other decisions cannot set validity.
* REQUEST_CHANGES removes assignment and requires a new draft submission before resubmission. Earlier versions and decision history remain protected.
* VERIFY and REJECT end reviewer evidence access immediately. Outcomes do not create a permanent privileged document-reading capability.
* POST `/{caseId}/revoke`: an independent password-confirmed operator may revoke a VERIFIED decision with a current version and reason. This administrative cancellation does not grant access to the evidence.

Rejection, withdrawal, revocation and expiry are terminal; submit a fresh case if needed. Expiry is effective at the deadline on reads and public status even before another write occurs. Starting a new case materializes an expired previous case, or revokes an approval whose business name no longer matches, with audit history.

This is **manual document review**, not an external KYC/registry identity check, proof of property ownership, certified e-signature or automatic tenant approval. It does not grant account roles, change financial permissions, approve provider bank destinations, or bypass screening, lease consent and payout controls. Operators must actually perform the checks they attest to; the API does not claim it can prove authenticity merely from an uploaded image.

Provider profile/directory responses use current unexpired BUSINESS approvals and the organization's current matching name, with additive verificationMethod=MANUAL_DOCUMENT_REVIEW and verificationValidUntil. Renaming the organization, withdrawal/revocation or expiry removes the badge immediately. Evidence, legal subject claims and case notes are not public.

## Irreversible file purge and retries

1. Archive first and resolve verification holds.
2. After retainUntil, POST `/{documentId}/purge` with requestId, version, reason, password and consent=true. This commits PURGE_PENDING and durable per-version deletion tasks. Access remains disabled. It does not delete payloads inside the initial transaction.
3. POST `/{documentId}/purge/process` with requestId, current version and reason. The current owner/administrator processes the committed tasks, deletes encrypted payloads and records PURGED. No restore is possible once purge is pending.
4. If deletion or final database work fails, the document remains inaccessible and pending. Retry processing with the current version; already absent encrypted files are safe to confirm again. A rolled-back completion cannot re-enable content. This is a retryable application job; no background scheduler is implied.

Purge removes payloads, not the protected metadata, hashes, grants, consent, decisions and audit history. Choose appropriate business/legal retention and backup policies for deployment rather than treating this minimum deadline as regulatory compliance.

## Transactions and security boundaries

Personal vault writes serialize on a user-scoped transaction advisory lock; organization writes lock the organization before authorization checks. Application share/read/revoke operations follow property → personal-vault ordering, with current application/property permissions rechecked. Verification writes lock the subject and enforce optimistic case versions; competing claims produce one winner. No vault path locks a property after taking a vault subject lock.

Request IDs bind scope, actor, action, target and canonical payload. Exact retries do not duplicate versions, grants, decisions, notifications or quota use. Changed payloads/actions/targets conflict. Password-confirmed retries still authenticate; passwords and key material are absent from persisted operation fingerprints and audit/event payloads.

Database constraints/triggers protect ownership, immutable versions, submissions, evidence membership, consent shares, events, operation evidence, irreversible grant revocation, terminal decisions and purge completion. Metadata/event/audit/notification changes commit together. Encrypted upload files are removed on rollback. Notification previews are generic and contain no names, statements, captions, document contents or passwords. In-app delivery is implemented; no external email/SMS/push, public document URL or automated identity provider call is added.

Migration V15 preserves V1–V14 and every previous notification source rule. No application data is used by tests: run `./scripts/test-foundation.ps1 -UseLocalDatabase` against the isolated zaza_foundation_test schema.
