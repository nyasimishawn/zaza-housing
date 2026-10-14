# Property images

Branch: `feature/property-images`. This adds private JPEG/PNG uploads and approved public listing galleries, using the shared controller/service/repository/model/DTO structure.

The completed property/listings/layer refactor is under review in [PR #1](https://github.com/nyasimishawn/zaza-housing/pull/1). This image work is on a separate branch, based on that pending work.

## Start and try it

Restart from the housing project folder:

```powershell
.\scripts\start-local.ps1
```

Flyway applies migration V4 for `property_images` and `listing_image_references`. Existing public snapshots remain readable with an empty images array. The backend does not publish existing uploads automatically.

Follow `api/property-images.http` in IntelliJ's HTTP client. Replace its account/token/organization/property/listing variables and `imagePath` with your own existing local photo. Upload a photo, read its returned ID/version, then select it explicitly in the listing publish request. Keep real tokens in the client's private environment.

## Upload and storage rules

- Only decoded JPEG and PNG images are accepted, verified from their bytes. Filename and declared MIME type do not choose the format or storage path. PDFs, SVG, GIF, video, corrupt data, and empty files are rejected.
- Maximum original file size: 10 MB; maximum multipart request: 51 MB (shared with video uploads). Images can contain at most 10 million pixels, with each original dimension at most 10,000 pixels.
- Images are resized to at most 1,600 pixels on the longest edge without enlarging smaller pictures. JPEG EXIF rotation/mirroring is applied before metadata is stripped; PNG alpha is preserved.
- A fresh raster is encoded as JPEG/PNG. Original EXIF/GPS, text metadata, uploaded filenames, and trailing bytes are not stored. Processed bytes are immutable; upload a new image to replace a photo.
- Descriptions (`altText`) are editable, trimmed, and limited to 200 characters. They may be empty. A property can have at most 30 active images.
- Local development stores generated UUID-named files in `.local-images` under the backend working directory. Configure another private directory with `ZAZA_IMAGE_DIRECTORY`; use an absolute path when placing it elsewhere. The default directory is ignored by Git and is not served as static content.
- Database writes and audits share a transaction. If an upload rolls back, its newly written file is removed. Archival retains the database/audit record and processed bytes privately; permanent retention cleanup is a future operation.

Orientation handling follows the rotation/mirroring behavior shown in the [Pillow EXIF transpose implementation](https://pillow.readthedocs.io/en/stable/_modules/PIL/ImageOps.html#exif_transpose). The backend implements this in Java and does not depend on Pillow.

## Permissions

Owners/admins in the owning business or its currently delegated agency can upload images, edit descriptions, and archive/restore images. Organization members can list metadata and view active images for authorized properties. Guests cannot read private image routes. Unrelated organizations cannot discover or mutate these records through known IDs, and agency access ends after delegation revocation.

Archived properties reject new uploads, description edits, and image restoration. Archiving an image remains allowed to stop its use. Archived images are excluded from default lists and cannot serve bytes through private or public routes; `includeArchived=true` includes their metadata for authorized members. Restore preserves the image ID while incrementing its version.

## Publishing a gallery

Use the existing listing publish endpoint, adding an optional ordered `images` array:

```json
{
  "version": 0,
  "propertyVersion": 1,
  "unitVersion": 0,
  "images": [{"id":"image-uuid","version":0}]
}
```

Select at most 20 unique, active images from the same property. Each selection needs the current image version, along with the existing listing/property/unit version checks. Stale versions return 409; foreign/archived images return 404; duplicates and invalid input return 400. Omitting images, or passing an empty array, publishes with no gallery and removes any previous selections.

The public listing response adds `images`, with each image's ID, approved description, MIME type, dimensions, and a relative API URL. These descriptions are public copy; render them as text in clients. Internal storage paths/keys, original filenames, upload actors, and image versions stay private.

Public image GETs require this image to be selected in the requested listing and the listing to remain visible under all publication/source/availability/delegation checks. Guessing an unselected image ID does not expose it. Public and private bytes use the verified MIME type, `X-Content-Type-Options: nosniff`, and `Cache-Control: no-store`.

Changing or archiving a selected image hides the listing and its image URLs until reviewed/re-published with current image versions. Restoring an image does not automatically republish it. New uploads and edits to unselected images do not change an existing gallery. Withdrawal, private inventory changes, or agency revocation also end public image access when they hide the listing.

## Endpoints

Private paths begin `/api/v1/organizations/{organizationId}/properties/{propertyId}/images` and require bearer authentication.

| Method | Suffix | Behavior |
| --- | --- | --- |
| POST | Empty | Multipart `file` with optional `altText`; private upload |
| GET | Empty | Metadata list; limit/offset and includeArchived |
| GET | `/{imageId}/content` | Active private image bytes |
| PUT | `/{imageId}` | Description edit: `{version,altText}` |
| PATCH | `/{imageId}/status` | Archive/restore: `{version,status}` |

Guest GET `/api/v1/listings/{listingId}/images/{imageId}` serves selected images of visible listings. Every upload/edit/status change appends audit history; publication records the approved gallery snapshot in the existing listing audit event.

## Verify

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Tests use `zaza_foundation_test`, with generated test images under `.work/image-tests-*`. They cover decoding/resizing, PNG metadata removal, JPEG orientation, MIME/filename spoofing, guest/private permissions, delegation/revocation, explicit gallery selection/order, stale/archived/foreign references, invalid and oversized uploads, audit rollback/file compensation, concurrent edits, and older snapshots without galleries. Generated test files stay private and ignored.

## Remaining media work

This slice handles static JPEG/PNG photos using private local storage. WebP/HEIC, ownership/document uploads, cloud object storage/CDN, malware-scanning infrastructure, retention cleanup, shared storage for multiple instances, and Flutter upload/gallery screens remain future work.

MP4/MOV video uploads are covered by `docs/property-videos.md`.
