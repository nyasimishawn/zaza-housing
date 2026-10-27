# Property ownership document review

Ownership verification extends the existing encrypted document vault and independent manual verification lifecycle. It records review of a particular owning organization's evidence for one property. It is not a land-registry integration or a legal guarantee of title. Publication continues under the existing listing rules; an upload or self-publication never creates a verified badge.

## API

- `POST /api/v1/organizations/{organizationId}/verifications`: the existing `requestId` and `details` fields, plus optional `propertyId`. When present, this creates `PROPERTY_OWNERSHIP`; without it, the existing business verification behavior is preserved. Personal verification cannot create ownership claims.
- `GET /api/v1/organizations/{organizationId}/verifications?propertyId={propertyId}&limit=20&offset=0`: only that owning organization's property claim history. Existing unfiltered lists include all its case types. `/verifications/status` continues to represent business verification alone.
- Existing case detail, revise, submit, withdraw, history and submission routes apply. Submitting requires current password, explicit consent, exact submission ID and current case version. The existing admin queue/claim/release/evidence/decision/revoke routes support the new kind.
- `GET /api/v1/organizations/{organizationId}/properties/{propertyId}/ownership-verification`: minimal current status for current owner/admin property managers, including a delegated agency. Delegation does not grant access to private ownership cases or scans.
- `GET /api/v1/listings/{listingId}/ownership-verification`: anonymous minimal status, only while the listing satisfies the same current publication/availability/source-version checks as public listing detail. Responses are `no-store`. Only `status`, `method` and `validUntil` are exposed; no property/organization IDs, claims, addresses or evidence.

## Subject, evidence and lifecycle

The server captures immutable property ID, owning organization ID/name, property name, address, city and country in the private draft response's `property` object. The draft is separate from password-confirmed submission; the user reviews this exact frozen subject before sharing evidence. Evidence must be active, retained, immutable `PROPERTY_OWNERSHIP` scan versions from that owning organization's vault. The claim uses its current organization name and property country.

There can be one current claim per property, alongside the organization's business case and claims for its other properties. Closed/expired/revoked claims retain history. Creating a replacement retires a verified claim whose validity or subject is no longer current. Revision and submission reject a changed property subject; withdraw and prepare a fresh claim.

Assigned reviewers must be independent of all current owning-organization members, current delegated-agency members, the creator and every submission preparer. Becoming related ends evidence/decision access, while release remains possible. Only the assigned independent operator sees the frozen subject/evidence. Approval requires password, completed manual checks and a validity deadline within 365 days covered by evidence retention. Rejection, requested changes, withdrawal, revocation, expiry, audit, notifications and exact retries reuse the existing lifecycle.

An approval is effective only before its deadline and while the reviewed property identity and organization name match current records. A mismatch suppresses the public badge and projects `REVOKED` on the private case; this is a current-subject check, not an extra persisted decision. Explicit withdrawal/revocation is terminal. New replacement claims retire the previous approval durably. Status is evaluated when read, never frozen into a published listing snapshot. Ownership approval cannot confer a business/provider badge.

Migration `V16__property_ownership_verification.sql` adds the property subject, constraints and independent active-case index. Existing case identities/history remain protected; previous migrations are unchanged. Deploy/migrate the backend before relying on the new Flutter workflow. No production database was used for validation.

## Verification

`DocumentVerificationTests` covers property/public privacy, independent review, approval/expiry/revocation, business/property case coexistence, UUID replay, immutable subjects, evidence category/owner/country, delegated-agency restrictions, changed property identity, membership revocation and hidden listings, alongside the existing vault/verification regressions. Run the foundation suite against a disposable `zaza_foundation_test` database before release.
