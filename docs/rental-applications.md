# Tenant profiles and rental applications

Branch: `feature/rental-applications`. This implements the tenant application workflow using the existing shared controller/service/repository/model/DTO layers. It follows the property video changes in PR #3; merge that PR into Develop before this application PR.

## Start and try it

From the housing folder:

```powershell
.\scripts\start-local.ps1
```

Flyway applies V6. Its tables are `tenant_profiles`, `rental_applications`, `application_unit_reservations`, `application_events`, `application_reviewer_notes`, and `in_app_notifications`. Existing accounts can create a tenant profile without receiving broader organization permissions or a new platform administrator role.

Use `api/rental-applications.http` with separate tenant and owner/agency login tokens. Complete the tenant profile, read the application preview for a published listing, create a draft using its listing version, then submit with explicit consent. Review as an owner/admin of the property owner or current delegated agency. Every successful mutation returns its current version; reload and use that version for the next action.

## Profiles and disclosure

A profile records legal name, international phone number, ISO country, city, employment status, and optional monthly income/currency. Income and currency must be supplied together; income is nonnegative with at most two decimal places. Phone numbers use `+` followed by 7–15 digits. Employment states are EMPLOYED, SELF_EMPLOYED, STUDENT, RETIRED, UNEMPLOYED, OTHER. Country and currency codes are validated and normalized. These fields are self-reported; this feature does not verify identity, employment, email, phone, or income.

GET/POST/PUT `/api/v1/me/tenant-profile` only reads or writes the authenticated account's profile. PUT uses `{version,details}`. Creation is separate from account registration, duplicate creation returns 409, and profile edits append audit snapshots.

Draft applications require a complete profile and a currently visible listing. Duplicates are prevented per applicant/unit while an application is active. The applicant receives a curated listing snapshot, their profile snapshot, details, status, and versions. Private property/unit/organization identifiers stay outside the applicant response.

Staff cannot list/read an initial draft, its history, or a draft withdrawn before submission. Submit requires the current application, profile, and listing versions plus `consentToShare: true`. Submission captures the selected profile and account email for authorized reviewers. Later private profile edits do not appear in an existing application until an information request and consented resubmission. After initial submission, application detail edits requested by reviewers are part of that shared application.

Only the applicant can list/read/edit/submit/withdraw their application. Only organization owners/admins with current property ownership or delegation can read submitted applications, history, and reviewer notes or make decisions. Ordinary organization members cannot access applicant contact/income data. Membership/delegation revocation takes effect on subsequent requests. Reviewers cannot make decisions on their own applications. Disabling an applicant account blocks approval.

Private application/profile/inbox responses use `Cache-Control: no-store`. Clients should render messages and notes as text.

## Lifecycle

| Current state | Actor/action | Next state |
| --- | --- | --- |
| DRAFT | Applicant submits with consent | SUBMITTED |
| SUBMITTED | Reviewer starts review | UNDER_REVIEW |
| SUBMITTED / UNDER_REVIEW | Reviewer requests information | INFORMATION_REQUESTED |
| INFORMATION_REQUESTED | Applicant edits/refreshes and resubmits with consent | SUBMITTED |
| UNDER_REVIEW | Reviewer approves current listing terms | APPROVED |
| SUBMITTED / UNDER_REVIEW / INFORMATION_REQUESTED | Reviewer rejects with a shared reason | REJECTED |
| Any active state, including APPROVED | Applicant withdraws | WITHDRAWN |
| APPROVED | Reviewer cancels with a shared reason | CANCELLED |

DRAFT and INFORMATION_REQUESTED allow applicant detail edits. All application mutations check the expected application version. Reviewer notes append without changing that version. Terminal applications are retained as history, and a new application can be created after rejection, withdrawal, or cancellation when the listing is available again.

Requested move-in dates cannot be in the past when creating, editing, or submitting, and household size is 1–20. Applicant messages, decision messages, and reviewer notes are limited to 2,000 characters. Decisions requiring a message reject blank input.

## Changed terms and reservations

GET `/api/v1/me/application-listings/{listingId}` returns the current curated listing and `listingVersion` to signed-in users. Public listing JSON keeps its existing contract. Draft creation and submit check the reviewed source; approval rechecks the listing/property/unit versions and current visibility. A withdrawn listing, unavailable unit, changed gallery, or changed pricing prevents approval of stale terms.

For changed terms: reviewer requests information; applicant reads the new preview, calls `refresh-listing` with the current application/listing versions, and resubmits. Refresh is restricted to editable states and the original listing. The backend never silently substitutes new rent/deposit terms during approval.

Approval reserves an ACTIVE/AVAILABLE unit and creates one reservation tied to that application in the same transaction. Property locks and database constraints prevent two approvals for the same unit. Other applications remain pending; reviewers can reject them or continue after a cancellation and fresh source review.

Approval increments the unit version and hides the public offer and its media. Reserved units cannot be edited or archived through normal unit APIs until the approved application is cancelled or withdrawn. Cancellation/withdrawal releases only that application's tracked reservation, checking its reserved unit version and availability. A changed/missing reservation returns 409. Release increments the unit version again and does not automatically republish the offer; the owner must review and republish with current source versions. An archived property can still close an application and release a reservation safely.

APPROVED means approved for the next lease-preparation step. It does not create a lease, signature, payment, active tenancy, or move-in. Reservations do not automatically expire in this version.

## History, notes, dashboards, notifications

Every workflow action appends a status event and an audit snapshot. Application history is visible to the applicant and authorized reviewers; it contains the shared decision/information messages. Private reviewer notes are in separate endpoints and never enter applicant history or notifications. Database triggers reject updates, deletes, and truncation of history and notes.

Review starts, information requests, approvals, rejections, and approval cancellations create personal in-app notifications for the applicant. These writes share the application transaction. A failed notification or audit write rolls back the entire decision, reservation, and event. No external email/SMS/push delivery is configured.

The applicant and property reviewer summaries contain counts for every application status, total applications, and needsAction. Applicant needsAction includes drafts/information requests; reviewer needsAction includes submitted/under-review applications. Reviewer counts exclude drafts that were never submitted.

Lists support limit 1–100, default 50, and nonnegative offset. Application lists accept a status filter. History/notes are chronological; applications and notifications show latest activity first. Notification lists support `unreadOnly`; marking an owned notification read is idempotent.

## Endpoints

Applicant base: `/api/v1/me/applications` (bearer authentication).

| Method | Suffix | Behavior |
| --- | --- | --- |
| POST / GET | Empty | Create draft / list own applications |
| GET | `/summary` | Own dashboard counts |
| GET / PUT | `/{id}` | Read / edit application details |
| POST | `/{id}/submit` | Versions + consent to share |
| POST | `/{id}/refresh-listing` | Select reviewed current listing version |
| POST | `/{id}/withdraw` | Withdraw; release approved reservation |
| GET | `/{id}/history` | Shared status history |

Reviewer base: `/api/v1/organizations/{organizationId}/properties/{propertyId}/applications`.

| Method | Suffix | Behavior |
| --- | --- | --- |
| GET | Empty / `/summary` / `/{id}` | Submitted application list, dashboard, detail |
| POST | `/{id}/review` | Start review: `{version}` |
| POST | `/{id}/request-information` / `/approve` / `/reject` / `/cancel-approval` | Decision: `{version,message}` |
| GET | `/{id}/history` | Shared history |
| GET / POST | `/{id}/notes` | Private notes; append uses `{version,text}` |

Inbox: GET `/api/v1/me/notifications`, GET `/api/v1/me/notifications/summary`, PATCH `/api/v1/me/notifications/{id}/read`.

## Verify and next work

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Tests use only the separate `zaza_foundation_test` schema. Coverage includes profiles, consent/snapshots, full information-review-approval flows, draft/tenant/organization isolation, membership/delegation revocation, validation, stale versions/terms, competing approvals, duplicate drafts, unit guards, cancellation/withdrawal, append-only history, personal notifications, and audit/notification rollback.

Flutter tenant/staff dashboards and application screens are not connected yet. Identity/document verification, protected application uploads, viewing requests, online payment providers, reservation expiry, retention/deletion policies, and external notification delivery remain future work. Lease drafting/signing and checkout are implemented in `docs/lease-management.md`.


## Lease handoff

A lease holding an approved application blocks application withdrawal/cancellation until that lease is resolved. Cancel or decline an unsigned lease before releasing its application reservation. Once both parties sign, the application becomes `LEASED` and its further lifecycle is managed through the lease APIs. Closing the lease changes the application to `CLOSED`; vacancy clearance and explicit listing republication are required before reletting. Both new statuses appear in application dashboard counts. `LEASED` remains part of the one-active-application-per-applicant/unit constraint; `CLOSED` is historical.


Lease rent billing, verified external payments and deposit reconciliation are now documented in [payments-rent-deposits.md](payments-rent-deposits.md). Configured billing adds move-in and checkout financial gates; unconfigured leases retain their prior behavior.
