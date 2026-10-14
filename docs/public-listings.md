# Public rental listings and guest search

Branch: `feature/public-listings`. This adds backend listing publication and guest discovery on top of private property/unit management.

## Start and try it

Restart the backend in your housing terminal:

```powershell
.\scripts\start-local.ps1
```

Migration V3 creates `rental_listings` and its search indexes. Existing properties and units stay private; migration does not publish them or insert sample listings.

Use `api/foundation.http` to create your account/organization and `api/property-management.http` to register a property/unit. Set the property to ACTIVE and the unit to ACTIVE/AVAILABLE. Then follow `api/public-listings.http` to create a draft and publish it.

Guests can open `http://127.0.0.1:8080/api/v1/listings` without a login. This is a JSON API. Flutter screens have not yet been connected to it.

## Publication workflow

1. Create a draft linked to an existing rental unit. One listing per unit prevents competing duplicates; edit/republish that record for future offers. Property and unit relationships are immutable.
2. Write a public title and description. They are separate from private property names/descriptions and unit labels. Keep private details out of this public copy.
3. Set `showExactLocation` deliberately. False keeps exact address, latitude, and longitude out of guest responses; city and country remain public. True includes the property's address and any stored coordinates when published.
4. Read the current listing, property, and unit. Publish with their current versions to approve the content and price/location source data together. Stale versions return 409 and require reloading/review.
5. A successful publication captures an explicit public snapshot and sets status PUBLISHED. Guest reads use that snapshot instead of serializing private records.
6. Use withdraw to stop publication. Editing listing copy withdraws a published listing until it is reviewed and republished.

Only owners/admins of the owning organization or currently delegated agency can create, edit, publish, or withdraw. Ordinary members can read the private listing for an authorized property. Unrelated organizations and platform administrators without membership cannot access private listing management. Guests have read-only access to visible publications.

## Visibility and stale offers

Public search and details use the same checks: listing PUBLISHED, property ACTIVE, unit ACTIVE and AVAILABLE, source versions still current, and publishing organization still the owner or delegated manager. A failed check hides the listing from search and returns 404 for guest detail.

Any property/unit version change makes the old snapshot ineligible. This includes price, availability, archival/restoration, property metadata, and agency delegation changes. Returning a unit to AVAILABLE or restoring inventory does not automatically republish an old offer. An authorized manager must review and publish again. Revoking agency access hides its publication, and the owner can take over by explicitly republishing.

The private listing's stored status may remain PUBLISHED while its sources have changed. Use `publiclyVisible` for effective visibility. Old public snapshots remain available to authorized managers and audit history. There is no destructive listing deletion endpoint.

Guest responses contain listing ID, public title/description, property type, country/city, approved exact location (or null), bedroom/bathroom counts, floor area, rent, deposit, currency, and publication time. They exclude property/unit IDs, internal unit labels, organization IDs, member/contact details, account IDs, versions, session tokens, and private property text. Public title/description should be rendered as text in clients. Public APIs send `Cache-Control: no-store` to avoid retaining stale withdrawn offers in HTTP caches.

## Endpoints

Private paths start with `/api/v1/organizations/{organizationId}/properties/{propertyId}/listings`, and require `Authorization: Bearer <token>`.

| Method | Suffix | Behavior |
| --- | --- | --- |
| POST | Empty | Create private draft: `{unitId, details:{title,description,showExactLocation}}` |
| GET | Empty | List private listings; supports limit/offset |
| GET | `/{listingId}` | Read draft/publication state and effective visibility |
| PUT | `/{listingId}` | Replace listing copy: `{version,details:{...}}` |
| POST | `/{listingId}/publish` | Publish reviewed snapshot: `{version,propertyVersion,unitVersion}` |
| POST | `/{listingId}/withdraw` | Withdraw: `{version}`; allowed on archived inventory |

Guest GET `/api/v1/listings` searches publications. Guest GET `/api/v1/listings/{listingId}` reads a visible listing. No other listing-management route becomes public.

## Guest search

| Parameter | Behavior |
| --- | --- |
| `q` | Optional 2-200 character text; indexed PostgreSQL full-text search of public title, description, and city |
| `city` | Case-insensitive exact city; 1-100 characters |
| `countryCode` | Valid ISO alpha-2 country code; case-insensitive |
| `type` | APARTMENT / HOUSE / COMMERCIAL / LAND / MIXED_USE |
| `currency` | Valid ISO currency; case-insensitive |
| `minRent`, `maxRent` | Inclusive decimal monthly-rent bounds, nonnegative, max two decimal places |
| `minBedrooms`, `maxBedrooms` | Inclusive counts from 0-100; zero supports studios |
| `sort` | NEWEST (default), PRICE_ASC, PRICE_DESC |
| `limit` | 1-100; default 50 |
| `offset` | 0-10000; default 0 |

Price filters and price sorts require a currency; amounts in different currencies are not compared or converted. Minimum bounds cannot exceed maximum bounds. Text search matches complete tokens with all query words required, without fuzzy/substring or wildcard matching. Database queries are parameterized; sort choices come from a fixed enum.

Response: `{"items":[...],"limit":50,"offset":0,"hasMore":false}`. Each sort includes listing ID as a stable tie-breaker; pagination retrieves one extra row to determine hasMore without an expensive total count. This first search uses PostgreSQL, with no external search service. Results may change as inventory/publication changes between page requests.

## Audit and verification

Creation, copy edits, publication, and withdrawal append structured previous/new snapshots to the protected audit log in the same transaction. Source changes use the property/unit audit events; they do not silently rewrite the original publication snapshot. Mutation locks and version checks prevent concurrent publication or source edits from overwriting an unreviewed change.

Run:

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

The existing suites and public-listing tests use the separate `zaza_foundation_test` schema. They check guest/private access, location disclosure, publication prerequisites, stale sources, prices/availability, archival, agency revocation, membership permissions, wrong parents, duplicates, concurrent publication, audit snapshots, safe search, filters, sorting, pagination, and invalid requests.

## Scope

Rental listings only. Sale listings, images/videos and media storage, maps/geocoding UI, enquiries/viewing appointments, saved/compared listings, applications, ownership verification, and Flutter integration remain future modules. Exact location support stores and exposes opted-in coordinates; it does not integrate a maps provider. Organization registration/property ownership remains self-declared until verification workflows are built.
