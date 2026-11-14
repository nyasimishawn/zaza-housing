# Lease changes, shared ownership and Flutter video workflows

The application client remains **Flutter/Dart**. The existing Spring Boot backend and shared controller/service/repository/DTO layers remain the application baseline. This records the project owner's decision to continue with Flutter rather than implement the blueprint's React client.

## Executed lease renewals and amendments

Use either `/api/v1/me/leases/{id}/changes` or `/api/v1/organizations/{organizationId}/properties/{propertyId}/leases/{id}/changes`.

| Method | Suffix | Purpose |
| --- | --- | --- |
| GET | empty | Read change history and signing evidence |
| POST | `/preview` | Generate exact proposal text and SHA-256 for review |
| POST | empty | Sign and propose a renewal or amendment |
| GET | `/{changeId}/document` | Download exact protected proposal bytes |
| POST | `/{changeId}/accept` | Other agreement party signs and accepts |
| POST | `/{changeId}/reject` | Other party rejects a pending change |
| POST | `/{changeId}/cancel` | Requesting party cancels a pending change (including a current owner administrator) |

Preview body: `version` and `terms`. Terms contain `kind` (`RENEWAL` or `AMENDMENT`), `effectiveDate`, `endDate`, `monthlyRent` and `agreementText` (20–20,000 characters). Creation adds the existing `LeaseConsent` evidence: exact digest, typed name, current password and explicit consent. Acceptance supplies current lease version and fresh evidence for the stored proposal. Resolve commands take current version and a message.

Only the actual tenant and a distinct current owner administrator can provide party consent. Delegated agencies can read through their existing manager scope but cannot sign addenda. One pending change exists at a time. Commands recheck current permissions under the existing property lock and reject stale versions. Proposal content, resolved decisions and consents are retained and protected against rewriting. Notifications, history, audit, signatures and billing changes commit together.

Renew before the existing term expires. A renewal starts at the current agreed end (end excluded) and extends it. An amendment keeps that end date and changes future rent/clauses. Changes take effect after today and at or after the original start, in chronological order after all accepted changes. Total duration is bounded to 600 calendar months. Deposit, currency, due day, parties and original start remain unchanged. These changes require an active property. Resolve termination proposals first. Early termination subsequently uses the renewed effective end and binds accepted addendum digests. Closing a lease cancels remaining pending change proposals.

The original executed revision, signatures and receipt never change. Accepted addenda supply a prospective rent schedule and effective end; they do not create a second unit reservation, release inventory or bypass checkout.

Move-out inspection windows follow the renewed effective end. Once a move-out handover is already accepted, renewals/amendments are blocked: complete checkout and arrange a new tenancy instead of reusing completed vacancy evidence.

### Billing consequences

If billing is not configured, its initial preview/open reads the accepted schedule. If billing is already open, acceptance retains original invoices, credits their replaced future portions and issues the new future schedule. Excess allocations are reversed back into unallocated existing funds; they are **not** refunded or transferred. Retained pre-effective rent stays due. Review/reallocate released funds through the existing finance workflow. Billing version increments so stale finance commands fail.

Partial months use the existing calendar actual-day policy, splitting at accepted effective dates. Finalization uses the rent applicable to each invoice period. Finalized billing cannot change. Custom credits on affected future invoices block acceptance; reconcile those exceptional financial arrangements before proposing replacement terms. Existing financial evidence is never silently rewritten.

## Ownership interests and transfer

Owner routes:

- GET `/api/v1/organizations/{org}/properties/{property}/ownership` — current controlling and co-owner percentages.
- POST the same route with `/preview` — review an exact ownership proposal.
- POST the same route — owner administrator signs and sends the reviewed proposal.

Organization inbox routes:

- GET `/api/v1/organizations/{org}/ownership-proposals?limit=50&offset=0` — inbound/outbound history.
- POST `/{proposalId}/accept`, `/reject` or `/cancel` under that inbox base.

Ownership terms contain `kind` (`COOWNER` or `TRANSFER`), recipient `organizationId`, `sharePercent` and a 10–2,000 character `reason`. Preview/create use current property version; create/accept require password-confirmed typed-name consent bound to exact SHA-256. The recipient must be a separate landlord/agency organization, accepted by a different account with current administrator authority. Sender authority is rechecked at acceptance; both organizations are locked in a stable order before the property. A changed property invalidates acceptance and requires cancellation and a fresh reviewed proposal.

Proposal responses include `fromOrganizationName` and `toOrganizationName` alongside stable organization IDs. Names are joined from the current business records for authorized proposal participants, including inbox and decision responses. This adds no public organization directory and changes no visibility checks. The signed proposal document remains the immutable record reviewed by the parties, even if a business is later renamed.

Co-owner proposals set the recipient's **resulting** share. Existing other interests stay unchanged; the controlling owner retains the remainder, always positive. Setting an existing co-owner to zero requests a bilateral release. A record of equity does not automatically grant management, tenant-document access or income distribution. Existing membership/delegation rules continue to govern operations. Recipients review their agreed interest and history in the ownership inbox.

A full transfer must be 100%, with all co-owner interests released through their own accepted proposals first. This release supports properties **without tenancy/service history**, pending applications, reserved/occupied units, active/pending agency mandates or open ownership-verification cases. Even completed historical leases/service cases block transfer: their immutable party/access records need a separate occupied-property novation and historical-access design. This limitation is enforced again at acceptance, not only in the UI.

Successful transfer changes the controlling organization, clears agency delegation/mandate, withdraws public offers and revokes a previously verified ownership claim. The new owner reviews inventory, submits new verification and explicitly republishes. Prior proposal/consent/audit evidence remains. These are application business-account records, not changes to a legal land registry.

## Video client integration

The existing property-video endpoints handle MP4/MOV validation, FFmpeg conversion, caption/version metadata, archival, authenticated content and selected-public-listing content. Flutter now supplies:

- Private file selection/upload (50 MB maximum, 120 seconds maximum, five active clips).
- Private playback with current property authorization, caption and archive/restore actions.
- Explicit ordered selections of video IDs **and versions** during publication review, alongside ordered photos.
- Fresh confirmation before replacing a previously published selection. Omissions remove public references; private uploads remain private until approved publication.
- Public playback from currently published listing details. No autoplay.

Native playback uses constructed same-backend routes and bearer headers and reloads with preserved playback state when the token rotates. Web private playback obtains authorized bytes through the normal account transport and uses a browser Blob URL, revoked after playback disposal. Tokens never enter URLs. Account changes, paused/hidden/detached lifecycle states and disposal close playback; brief inactive interruptions keep it. Playback errors use a generic retry/access message rather than displaying private URLs, credentials or native diagnostic details. File selection defers reads; native multipart uploads stream bounded file ranges and show progress with a 30-minute video timeout. Browser HTTP still buffers the request, so its progress describes preparation. Failed uploads retain selection; uncertain mutations require inspecting/reloading before any manual retry.

## Verification and migration

V31 adds immutable lease change/consent history and billing replacement evidence. The original rent-period unique index becomes a lookup index because original credited and replacement invoices coexist. Property locking plus lease versions serialize acceptance. V32 adds co-owner interests and immutable bilateral ownership proposals/consents. No existing executed agreement, original ledger entry or verification history is deleted.

Run `./scripts/test-foundation.ps1 -UseLocalDatabase`; the test profile fixes JDBC/Flyway to `zaza_foundation_test`. Feature tests cover independent consent, stale versions/digests, unchanged original receipts, future invoice replacement, privacy, immutable evidence, co-owner totals, cancellation, transfer revocation and occupied-transfer blocks. Existing payment, property and real video tests remain part of the full foundation suite.
