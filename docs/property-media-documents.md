# Property media and document workspace

Additive migrations V30/V31 extend the existing video, listing and encrypted-vault APIs. Existing synchronous video uploads and image-only documents remain compatible.

## Video queue and posters

Base: `/api/v1/organizations/{org}/properties/{property}/video-uploads`.

- `POST` known-length multipart, parts `metadata` (JSON: requestId UUID, propertyVersion, caption <=200 chars) and `file`; returns 202 job metadata.
- `GET ?limit=20&offset=0`, `GET /{id}`: bounded status/history; no source keys or private filesystem details.
- `POST /{id}/cancel`, `POST /{id}/retry`, JSON `{ "version": currentJobVersion }`.
- Statuses: QUEUED → PROCESSING → READY/FAILED; QUEUED/PROCESSING → CANCELLED. READY includes videoId. Transfer completion does not imply conversion completion. Encoding progress is emitted by FFmpeg and clamped below 100 until commit.
- A replayed actor/property/request identity returns its original job only when org, source digest/size, property version and caption match. Different content returns 409.
- Maximum five active videos/property, three pending jobs/property, twenty new uploads/hour/actor. Retry permits at most three processing attempts with a ten-second cooldown, only for interrupted/unavailable processing while the source is retained. Invalid media/access changes require a new reviewed request.
- Durable PostgreSQL jobs are claimed with SKIP LOCKED, a lease UUID and two bounded workers. Encoding holds no property/job locks. Before persistence the active uploader and current management access are rechecked, then property and job are locked in that order. Cancellation or an expired lease prevents publication.
- `zaza.media.jobs.enabled` defaults true; `zaza.media.jobs.poll-ms` defaults 3000. Tests disable background claims and use the same synchronous processing entry. Ten-minute interrupted jobs become failed. Successful/cancelled source files are removed; failed sources are eligible for cleanup after 24 hours. Hard-crash filesystem orphan reconciliation and production storage monitoring remain operational follow-ups.
- Existing video limits remain MP4/MOV, 50 MiB, <=120 seconds. FFmpeg creates normalized H.264/yuv420p MP4 and a JPEG poster up to 640px edge/2 MiB. Metadata exposes SHA-256 digests/lengths, never storage keys.

Additional routes: `GET .../videos/{id}` metadata; `GET .../videos/{id}/poster` private JPEG; `GET /api/v1/listings/{listing}/videos/{id}/poster` approved public JPEG. Poster access follows the same active-video and reviewed-listing permissions as playback. Existing range/seek content endpoints remain unchanged.

## Publication review

`GET .../listings/{id}/media-selection` returns listingVersion and ordered `images`/`videos` id-version references, including stale prior selections. The read uses a repeatable-read snapshot. Clients preserve matching versions and explicitly review changed/removed selections. Existing publish bodies already accept up to 20 photos and 5 videos; no API silently clears a selection. Editing/archiving/restoring selected media still makes the public offer unavailable until reviewed again.

## PDF vault

Uploads use existing document routes and metadata. PDF detection checks actual bytes. Raw input stays in memory under the existing bounded document multipart filter (10 MiB input). At most two parser processes run concurrently, each `-Xmx256m`, headless, 45-second wall deadline and bounded stdin/stdout. The packaged Spring Boot JAR launches the same worker through PropertiesLauncher.

PDFBox 3.0.8 renders up to 20 pages (160 DPI, <=2000px edge, <=60 million aggregate pixels), rejects encryption/XFA/extreme sizes, then creates a fresh raster-only PDF capped at 20 MiB. Original scripts, interactive forms, links, attachments, metadata and digital signatures are not retained. This is not an authenticity certificate or antivirus scanner. The canonical result reuses AES-256-GCM encrypted immutable version storage, digest checks, retention, audit, purge, consent and reviewer rules. PDF upload idempotency binds the original source digest; existing image upload hashing stays compatible. Processing occurs outside the subject database lock; active-user and vault permissions are rechecked before persistence.

Versions add `pageCount`, default 1 for previous scans. Metadata dimensions describe page one. New `GET .../versions/{versionId}/pages/{pageNumber}` and equivalent application/assigned-verification evidence paths return no-store JSON: versionId, pageNumber, pageCount, image contentType, SHA-256, dimensions, byteSize and base64 bytes. Authentication/consent and full encrypted-version integrity checks run before extraction. No public PDF route is added.

Vault list adds optional `q` (title substring, <=150 chars), category and kind (`PDF`/`IMAGE`); existing status and pagination remain valid.

## Private property files

Base: `/api/v1/organizations/{org}/properties/{property}/documents`.

- `GET ?limit=20&offset=0`: current organization's links, exact linked version plus current vault metadata.
- `POST`: requestId UUID, propertyVersion, documentId, documentVersionId; returns 201. A repeated identity with the same scoped document/version is idempotent. A changed/detached identity conflicts. Max 100 active links; duplicates rejected.
- `DELETE /{linkId}`: 204; idempotent soft unlink with audit. Vault content and existing consent shares remain.

Both property access and current OWNER/ADMIN membership of that exact vault organization are required. Personal/foreign vault documents cannot be attached. Links pin immutable versions and confer no content rights: all content still passes the existing vault/consent/reviewer endpoints. A newer upload, property delegation or listing publication cannot broaden document access. Archived/purged documents are not readable through these links.

## Validation and deployment

Focused PostgreSQL/HTTP tests cover queue replay/cancellation/capacity/revoked access, real conversion/posters/range/publication, normalized PDFs, encrypted storage and integrity failures, page authorization/version immutability, vault filtering and property link permissions. Regression runs use a fresh disposable `zaza_foundation_test` cluster on a random non-5432 port, never demo/production data, with environment imports disabled. The deployable JAR worker is checked separately using a synthetic PDF.

Use existing document key and FFmpeg setup scripts. Do not commit keys, .env, uploaded files, generated binaries or test logs. Production object storage/CDN, multi-instance shared filesystem configuration, cleanup monitoring and iOS signing are separate deployment concerns. Keep all replicas on the same configured media storage if multiple workers are deployed.

## Delivery verification

The complete backend suite passed 523 tests with no failures, errors or skips against an isolated disposable PostgreSQL cluster; bootJar and the packaged-JAR PDF worker passed. The final complete video suite passed 20 cases, including cancellation after real encoding and retained-source retry/cooldown. These include two cases added after the full-suite run, for 525 unique passing backend cases. No production/demo database or environment files are used.
