# Maintenance and service requests

Branch: feature/maintenance-service-requests, based on Develop after lease PR #5 and payment PR #6 merged. V10 adds provider profiles/offers, work requests, assignment/quote/visit evidence, private photos, disputes, reviews and notification links. All additions use the shared controller/service/repository/model/DTO layers.

## Workflows and responsibilities

MAINTENANCE is property work managed by the current owner or delegated agency. The occupant can submit a request from their ACTIVE lease, respond to questions, consent to visits, review completion and raise a dispute. An owner/agency OWNER or ADMIN may also submit a maintenance request for that active lease. Requests need a category, title, description and priority. LOW/NORMAL/HIGH/URGENT are triage labels; they do not provide emergency dispatch, response guarantees or automated escalation.

SERVICE_BOOKING is a tenant's personal booking with a selected provider offer, such as moving or cleaning. It can be submitted from the tenant's SIGNED or ACTIVE lease during the current term. The tenant authorizes its quote and confirms completion. Landlords/agencies cannot see these private bookings through property maintenance APIs. All requests currently require a lease; vacant-unit/common-area work, non-tenant customers and arbitrary external service addresses are future extensions.

The two workflows share work history and scheduling infrastructure while keeping access and paying authority distinct. Provider completion is a report of work performed, never proof of payment. This slice does not charge a tenant, create a rent invoice, transfer funds, release payment or pay out a provider. Service-specific payments, revenue accounting and payouts require a later integration with billing/payment providers.

## Provider profiles and discovery

A SERVICE_PROVIDER organization OWNER/ADMIN can create a private DRAFT service profile with displayName, description and up to 20 country/city service areas. ISO country codes are validated. City '*' explicitly covers every city in that country. Supported categories: MOVING, PLUMBING, ELECTRICAL, CLEANING, CARPENTRY, PAINTING, APPLIANCE_REPAIR, SECURITY, INTERNET, LANDSCAPING and GENERAL_MAINTENANCE.

Service offers have a category, title, description, two-decimal ISO currency, priceUnit and optional amount. QUOTE_REQUIRED omits the amount; FIXED/HOURLY require a positive amount. Listing prices do not automatically become job charges. Every external job still requires a specific quote and paying-customer consent. Quote amounts are total job amounts; hourly multiplication, taxes and extras are not calculated.

Activate an offer before publishing its profile ACTIVE. Profiles may be PAUSED/DRAFT and offers DRAFT/ACTIVE/ARCHIVED. Only ACTIVE profiles with ACTIVE offers appear publicly. Directory searches filter by category, country and city and use limit 1-100/nonnegative offset. Publication is business-controlled and always labeled NOT_VERIFIED. No KYC, business verification badge, license validation or background-check claim is created.

Provider profile/offer writes use their current version and append audit evidence. Dispatch checks the selected offer version, category, active status and service area under the provider organization lock. Assignment freezes the complete offer snapshot, so later edits or archival do not rewrite existing agreements. Profiles can pause new intake while existing accepted jobs continue.

## Maintenance lifecycle

SUBMITTED → NEEDS_INFORMATION → SUBMITTED is available for clarification. Managers then TRIAGE with a priority and reason, reject with a reason, or cancel before work starts. The occupant may withdraw their maintenance report before triage. A manager can assign an active matching service offer, creating ASSIGNED work, or choose internal handling from TRIAGED. Cancellation removes provider access, including for assignments the provider had not accepted. The previous provider receives only a generic assignment-ended notice.

External provider: ASSIGNED → provider ACCEPTED, or provider DECLINED. Declining returns only a decision acknowledgement and removes provider access. Maintenance returns to TRIAGED for reassignment; a personal booking becomes REJECTED and the customer may select another offer. An accepted provider may release a job before starting, with the same access revocation. Original assignment snapshots and shared business history are retained. Current assignment updates and photos are not transferred to a replacement provider's private view.

Internal maintenance: TRIAGED → assign-internal → ACCEPTED. A manager acts as the worker. Internal handling has no marketplace quote or provider rating, and does not create an internal service charge. It still needs an accepted visit and occupant completion review.

After acceptance, the provider proposes a quote. Only an OWNER/ADMIN acting through the property's owning organization and distinct from the occupant may authorize maintenance costs. Agency management delegation permits coordination, not owner spending authority. Occupants cannot inspect maintenance procurement quotes. Personal-booking quotes are private to the customer and current provider, and the customer authorizes them.

Quotes freeze amount/currency/scope/expiry and require explicit consent to the exact current quote ID and work version. Expiry must fall in the next 30 days; an unaccepted expired quote cannot be approved. Accepted quotes remain evidence after their offer expiry. A replacement quote requires fresh approval and invalidates any pending visit proposal. Once work is scheduled or started, the provider cannot silently replace its quote; changes require returning to the appropriate review state.

## Visits and site access

The assigned provider, or internal manager, proposes a startAt/endAt/accessInstructions after quote authorization where required. Visits last 15 minutes to 24 hours and fall within the next 90 days. Instants use explicit UTC/offset timestamps. A personal service visit must fit inside its lease term.

Only the actual occupant/customer can accept the current visit with consent=true. A manager's quote approval is not occupant entry consent. Acceptance reserves the provider's calendar; overlapping confirmed visits across different properties/customers are rejected. Adjacent visits are allowed. Each provider organization currently represents one service capacity; multi-crew calendars, availability templates and external calendar synchronization are future extensions. Pending proposals do not reserve capacity.

Either party may request rescheduling before work starts. It clears the selected visit and returns to ACCEPTED, requiring a new proposal and fresh occupant consent. Old proposals/acceptances remain immutable. A worker can START only in the accepted visit window and while the property is ACTIVE and the unit remains under the original lease hold. Personal-booking starts also require the current lease term. No new visit/start is permitted after unit release. Existing maintenance may be reconciled during move-out while the original unit hold remains.

The provider's initial ASSIGNED view includes country/city and the request, with exact address/unit label withheld until acceptance. Provider responses omit lease/property/unit/tenant IDs, tenant contact/KYC/application data and rent/payment history. Shared request text/photos are user-supplied work context. Provider access is limited to its current assignment and current OWNER/ADMIN membership; removal/reassignment/revocation takes effect on the next request.

## Completion, disputes and ratings

IN_PROGRESS → submit-completion with a summary of at least 20 characters → AWAITING_CONFIRMATION. Optional private photographs provide supporting evidence. A worker cannot confirm their own report through the occupant API.

For a personal booking, customer confirmation makes the job COMPLETED. For maintenance, occupant confirmation makes it AWAITING_APPROVAL; an owner/agency manager then approves completion. Completion records server-clock time. There is no timeout-based automatic customer confirmation.

A participant may report a blocker/dispute while scheduled, in progress or awaiting review. A unique immutable dispute case records the raising party and reason. After COMPLETED, only the customer may reopen work and only within 14 days; providers cannot reopen jobs to suppress negative ratings. The customer for personal bookings, or coordinator for maintenance, may request REWORK. Rework preserves the dispute and prior evidence, clears visit/completion selection, returns to ACCEPTED and requires fresh visit consent. Existing approved quote scope remains; any new quote requires fresh paying-customer approval. A raising party can withdraw its own dispute; the previous active/review state is restored without fabricating a completion report. There is no force-complete/adjudication endpoint.

Only the occupant/customer may publish a 1-5 rating/comment after completion, with publishReview=true. One immutable review is allowed per assignment. Members of the provider organization cannot rate their own organization. Directory aggregates/public reviews include only the current completed assignment; reopening a job removes its review from public results until completion is resolved. Reviews expose the selected rating/comment and time, not customer identities or premises IDs. Written comments are explicitly public customer content; moderation and formal appeals are future work.

Lease completion can proceed during reconciliation, preserving the unit's UNAVAILABLE checkout state. Final release-unit is blocked while maintenance is IN_PROGRESS, awaiting completion review/approval or DISPUTED. Clear that workflow before vacancy/financial clearance. Purely submitted/assigned/scheduled work does not itself block release; live site checks prevent it starting after release. Personal bookings do not grant landlords visibility or add an implicit maintenance hold to checkout. These checks extend the existing lease service; existing leases without work requests retain their behavior.

## Notes, photographs and evidence

Shared notes form a private participant timeline. INTERNAL notes/images are restricted to current owner/agency administrators; they do not appear in occupant/provider histories or their inboxes. A replacement provider sees shared original request context and its current assignment's updates, not the previous provider's assignment-specific notes/photos. Metadata and binary reads enforce the same scope, including direct image IDs.

Evidence uploads accept actual JPEG/PNG content, at most 10 MB/10 million pixels, reuse the existing image processor, resize to a 1600-pixel edge and strip original metadata. Client filenames/storage paths are never trusted or exposed. Generated files stay in private image storage and cannot be selected as property listing photos. Up to 30 evidence images per work request are retained. Captions and visibility are explicit. Images can be added during open work/reconciliation, not after closure. Images/history are append-only in this slice; retention/redaction operations require a separate controlled workflow.

Storage writes register transaction rollback cleanup. Failed audit/notification/evidence writes roll back metadata, status, operation keys and newly written files. Files and generated logs are excluded from Git. Local storage is reused for development; cloud object storage/antivirus/media moderation and lifecycle cleanup are future infrastructure.

## Concurrency, audit and notifications

Creation takes a UUID requestId, scoped to its lease. Subsequent mutations take requestId and current work version. Keys bind actor/action/target/payload using canonical request digests. Exact authorized retries return the current view without duplicate operations, events or files. Reusing a key for another payload returns 409; fresh stale versions return 409. Replays still require current access: a declined/released/reassigned provider may receive 404 after losing its assignment. Profile/offer edits use their own versions.

Property locks serialize work with lease/unit changes. Provider organization locks follow the property lock and serialize calendar acceptance across properties. Membership/assignment permissions are rechecked after locks. SQL stays in repositories. Original request identity/content, assignment snapshots, quotes/acceptances, visits/consents, disputes/resolutions, images, reviews, events and operation records are protected against rewriting/truncation. Database owners retain administrative control; production needs restricted runtime privileges.

Business events, audits and in-app notifications commit together. Maintenance updates notify the occupant and current owner/agency managers; assigned providers receive permitted shared updates. Personal bookings notify only the customer and provider organization. Actor notifications are skipped and duplicate recipient roles are deduplicated. Internal notes only notify managers. Existing application/lease/payment notification fields remain compatible; work messages add workRequestId/workRequestUrl and leave unrelated application/lease links null. Following inbox URLs always rechecks current permissions. Email, SMS and push delivery are not enabled.

## API map

Public directory:

- GET /api/v1/service-providers?category=PLUMBING&country=KE&city=Nairobi
- GET /api/v1/service-providers/{providerId}
- GET /api/v1/service-providers/{providerId}/offers
- GET /api/v1/service-providers/{providerId}/reviews

Provider profile base: /api/v1/organizations/{organizationId}/service-profile

GET/POST base read/create; PUT base edits version/details; POST /state changes version/status. GET/POST /offers list/create; PUT /offers/{offerId} edits version/details; POST /offers/{offerId}/state changes version/status. States, pricing and versions are validated in services.

Private request bases:

| Participant | Base |
| --- | --- |
| T: occupant/customer | /api/v1/me/work-requests |
| M: owner/agency coordinator | /api/v1/organizations/{organizationId}/properties/{propertyId}/work-requests |
| P: provider | /api/v1/organizations/{organizationId}/service-jobs |

All support GET base with status/kind/priority filters and pagination, GET /summary, GET /{id}, and GET /{id}/history, /quotes, /visits, /disputes and /evidence. Occupants cannot read maintenance /quotes. P additionally supports GET /calendar?from=...&to=... with a positive window of at most 31 days.

T/M POST base creates with requestId, leaseId, kind, category, priority, title and description. SERVICE_BOOKING also needs offerId/offerVersion and is T-only. Maintenance creation never preselects a provider; coordinate assignment after triage.

Commands below /{id}:

| Command | Roles | Extra fields beyond requestId/version |
| --- | --- | --- |
| /request-information, /triage | M | priority, message |
| /respond | T | message |
| /assign | M, T for personal booking | offerId, offerVersion, message |
| /assign-internal | M | message |
| /accept, /decline, /release | P | message |
| /reject | M | message |
| /cancel | T/M, before permitted work stages | message |
| /notes | T/M/P | visibility SHARED/INTERNAL, message |
| /quotes | P | amount, currency, validUntil, scope |
| /quotes/{quoteId}/accept | T for personal, owning M for maintenance | consent=true, message |
| /visits | P or internally handling M | startAt, endAt, accessInstructions |
| /visits/{visitId}/accept | T | consent=true, message |
| /reschedule | T/M/P | message |
| /start | P or internally handling M | message |
| /submit-completion | P or internally handling M | summary |
| /confirm-completion | T | consent=true, message |
| /approve-completion | M | consent=true, message |
| /disputes, /withdraw-dispute | T/M/P, subject to state/raising party | message |
| /rework | T for personal booking, M for maintenance | message |
| /reviews | T | rating, comment, publishReview=true |

POST /{id}/evidence uses multipart file, requestId, version, caption and visibility (default SHARED). GET /{id}/evidence/{imageId}/content streams authorized content. All successful commands return 200 with the current participant-specific WorkView; provider decline/release returns only id/version/status after access removal. Private responses are no-store. Unknown private resources return 404; role/visibility violations 403; stale/state/calendar/uniqueness conflicts 409; invalid data 400; oversized images 413.

## Verification and next work

Run .\scripts\test-foundation.ps1 -UseLocalDatabase. Tests target only zaza_foundation_test, use real HTTP/PostgreSQL and a test clock, and keep image fixtures in an ignored test directory. Coverage includes directory states, publication/area/category/version checks, private bookings, participant isolation/revocation, quote/entry authority, assignment snapshots, internal/external work, overlapping/adjacent visits, stale/concurrent/idempotent writes, review gating, disputes/rework, private image scope/rollback, immutable evidence, notification rollback and checkout guards.

Runnable examples: api/maintenance-service-requests.http. Flutter screens, provider verification, service payments/payouts, common-area/vacant-unit work, multi-crew availability, external messaging, formal dispute adjudication, moderation, retention operations and SLA automation remain future work.
