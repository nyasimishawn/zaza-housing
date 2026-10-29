# Property and service discovery

This feature implements the blueprint's shared Discover/Search/Map and Find a Home workflows while retaining the existing Flutter client. It adds public discovery plus private saved items, searches, match alerts and rental comparison boards.

## Public discovery

| GET endpoint | Behavior |
| --- | --- |
| `/api/v1/discovery/search` | Published properties or public service providers; filters and stable pagination |
| `/api/v1/discovery/places?q=Ruiru&countryCode=KE` | Attributed public place centres; 2–100 search characters, up to 20 results |
| `/api/v1/discovery/comparison?ids=<uuid>,<uuid>` | One to four distinct rentals, read from their current public projection |
| `/api/v1/discovery/map` | Same-origin tile template, provider attribution and place-source notice |
| `/api/v1/discovery/tiles/<revision>/{z}/{x}/{y}.png` | On-demand cached basemap tiles at zoom 2–19 |

Search accepts `kind=PROPERTY|PROVIDER`, `q`, `countryCode`, `city`, `sort`, `limit` (1–100, default 20) and `offset` (0–10000). Property filters are `type`, `currency`, `minRent`, `maxRent`, `minBedrooms`, `maxBedrooms`. Provider filters are `category` and `verifiedOnly`; categories require active offers. Text search matches public titles/names and descriptions. Country and city filters match canonical published values, case-insensitive for city.

Geography supports `latitude`, `longitude`, `radiusKm` (greater than zero, at most 500 km), and/or a complete `north,south,east,west` viewport. Longitudes crossing the dateline are supported. `DISTANCE` sort requires a centre; provider geographic queries require a country. Other sorts are `NEWEST`, `NAME`, `PRICE_ASC`, `PRICE_DESC`. Price filtering/sorting requires one currency. Amounts use decimal arithmetic; currencies are never converted.

Only existing public visibility rules are used: current publication/version, active unit/inventory/organization, account moderation, publication media and authority remain authoritative. A withdrawn, suspended or stale publication cannot be recovered through discovery, saved items, comparisons or alerts. These JSON responses use `Cache-Control: no-store`.

## Location and trust

- `EXACT` property pins come solely from the owner's current published projection after explicit exact-location disclosure. Discovery never falls back to private inventory coordinates.
- `AREA` pins are GeoNames public place centres. Radius and viewport matching use that centre, which is approximate. Unmapped cities stay discoverable through list search.
- Provider pins represent declared service areas. Countrywide providers can be represented at the searched area's centre with `COVERAGE`; this is not a business address or travel distance.
- V27 imports 6,957 Kenya populated places from GeoNames, retrieved 2026-10-29, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Source: [GeoNames Kenya](https://download.geonames.org/export/dump/KE.zip), [data description](https://download.geonames.org/export/dump/readme.txt). Attribution is retained in the migration and shown in the client. Place centres are approximate, without an accuracy warranty. Duplicate city names with no established population remain unmapped instead of choosing an arbitrary location. Additional countries require a reviewed migration/dataset.
- Current, unexpired manual document review determines verification badges. A badge is not a guarantee of ownership, work quality or availability.

## Private saved workspace

Authenticated endpoints are rooted at `/api/v1/me/discovery`. Collections are `items`, `searches`, `comparisons`, `alerts`. GET supports lists (`limit`, `offset`; items also `folder`) and `/{id}`. POST creates the first three collections; PUT `/{id}` edits; POST `/{id}/delete` removes. POST `/alerts/{id}/read` marks an alert read.

Create/edit/delete commands carry a UUID `requestId`. Edits/deletes also require the reviewed `version`. A command's fingerprint includes its content, actor and target. Replaying the same request is safe; reusing its ID with different content conflicts. Session and active account are rechecked, mutations lock the actor row, and private IDs are account-scoped. Audit events contain action/target metadata, not private notes or search terms.

| Collection | Fields and limits |
| --- | --- |
| Items | `kind`, `targetId`, `folder` (80), `note` (1000); 500/account; duplicate saves preserve the original note/folder |
| Searches | `name` (100), `query`, `frequency=OFF|HOURLY|DAILY`; 25/account |
| Comparisons | `name` (100), `listingIds` (one to four distinct public rentals); 20/account |
| Alerts | Generated privately from a saved search; read state and current target availability |

Saved rows retain target IDs and personal metadata rather than public snapshots. Missing/withdrawn targets return `available:false,item:null`. Comparisons show current rent, deposit, attributes, location, current review status and declared amenities. `comparableCurrency` is true only with at least two available rentals in the same currency; no reservation or price promise is made.

## Durable in-app matching

Creating a search baselines all current matching identities. Polling starts new-match alerts only for identities not previously seen in that generation. Criteria changes, or reactivating paused matching, create a fresh baseline and remove the old generation's alerts. Renaming or changing an active cadence preserves the baseline. Re-publication of the same identity does not generate another alert in that generation.

The scheduled worker polls every 60 seconds, takes up to 20 due searches and creates at most 50 new target alerts per search/cadence. Additional unseen results are considered on later scheduled scans. Transactional row locks with `SKIP LOCKED` and unique search/generation/target keys prevent duplicate concurrent delivery. Errors schedule a five-minute retry with a generic message. Inactive accounts are excluded. Alert reads recheck current criteria, generation and public availability. The worker does not bump user-edit versions.

Alerts appear in the app's saved discovery workspace. Email, SMS and push delivery are not part of this feature. A scan time is a completed check, not a promise that a new match exists.

## Declared amenities and migration compatibility

V28 adds draft `amenities` and `published_amenities` to `rental_listings`. Listing commands accept up to 30 nonblank tags of 40 characters each; omitted tags preserve an existing draft on legacy updates. Tags are trimmed/deduplicated and copied to the public amenities field only on publication. Editing existing published copy follows the existing withdrawal/review workflow.

The legacy `PublicListingResponse` schema is unchanged. Application and signed lease snapshot hashes therefore remain stable. Amenities are returned separately on `DiscoveryItemResponse`; they are publisher declarations, not independently verified attributes. Existing listings start with empty tags.

## Basemap operation

Spring properties (environment variable equivalents are supported by Spring):

| Property | Default |
| --- | --- |
| `zaza.discovery.map-enabled` | `true` |
| `zaza.discovery.tile-template` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` |
| `zaza.discovery.tile-directory` | `.local-map-tiles` |
| `zaza.discovery.tile-cache-mb` | `200` (1–10000) |
| `zaza.discovery.tile-attribution` | `© OpenStreetMap contributors` |
| `zaza.discovery.tile-attribution-url` | `https://www.openstreetmap.org/copyright` |
| `zaza.discovery.alerts-enabled` | `true` |
| `zaza.discovery.alert-poll-ms` | `60000` |

The server uses a fixed HTTPS PNG tile template, stable identifying User-Agent, four upstream connections, bounded response sizes/timeouts and no redirects. Client requests cannot choose an upstream host. Provider configuration/keys remain server-side; tile URLs include a template revision. Only visible tiles are loaded on user interaction. Tiles are cached for seven days on disk and in browsers; unexpired cache files are not evicted for quota. Cache exhaustion returns an unavailable basemap while list search remains usable. Provision persistent disk capacity and monitor errors/capacity for the deployment's actual viewport traffic. The file cache is per process filesystem; a shared or replicated production deployment needs an appropriate cache/provider design.

The proxy forwards the browser's HTTP(S) origin Referer while stripping private path/query data. Attribution stays visible and linked. Follow the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/): no bulk downloads, prefetch or offline maps. The default community service is best effort. Choose a suitable contracted/self-hosted provider and its attribution/configuration before substantial public traffic. No external geocoder or GPS collection is introduced. `allow-local-http` exists only for isolated mock tile tests and must remain false in deployed configuration.

## Validation

Run `.\scripts\test-foundation.ps1` for an isolated disposable PostgreSQL cluster, or `-UseLocalDatabase` only with an explicitly separate `zaza_foundation_test` database. Never target a demo or production database. The suite includes real API authorization, visibility and publication tests plus local HTTP tile-server tests; automated checks do not download public OSM tiles. See `DiscoveryWorkspaceTests` and `MapTileServiceTests`.
