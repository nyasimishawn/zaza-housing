# Property videos

This adds private MP4/MOV uploads and explicitly approved public listing videos in the shared controller, service, repository, model, and DTO layers. The source branch is `feature/property-videos`, based on Develop after the image PR merged.

## Run locally

From the housing project folder, install the portable Windows tools once, then restart the backend:

```powershell
.\scripts\install-video-tools.ps1
.\scripts\start-local.ps1
```

The installer downloads FFmpeg 9.0.2 from the Windows build provider linked by the [official FFmpeg download page](https://ffmpeg.org/download.html), verifies its pinned SHA-256, and keeps its licenses/notices under `.tools/ffmpeg`. Binaries and the downloaded package are ignored by Git. The installation does not modify global PATH. Both tools were installed locally for this feature.

For Linux, macOS, or another installation, set `ZAZA_FFMPEG` and `ZAZA_FFPROBE` to executable paths (or commands available on PATH). Defaults on this Windows project are `.tools/ffmpeg/bin/ffmpeg.exe` and `.tools/ffmpeg/bin/ffprobe.exe`. A backend without video tools still starts; an attempted upload returns 503 with a configuration message.

Flyway applies V5 for `property_videos` and `listing_video_references`. Older published snapshots deserialize with an empty `videos` array. Existing photos and selections retain their behavior.

Use `api/property-videos.http` in IntelliJ's HTTP client. Replace the token, organization/property/listing IDs, source versions, and local `videoPath`. Keep tokens in the client's private environment. Upload a clip, then use its returned ID/version in an explicit publish selection.

## Upload and processing

- Maximum input file: 50 MiB; maximum multipart request: 51 MiB. Image uploads still have their own 10 MiB limit.
- MP4/MOV containers are verified from the uploaded bytes and FFprobe. Accepted video codecs: H.264, H.265, MPEG-4. Use one video track and at most one AAC, MP3, or PCM (16/24-bit little-endian) audio track. Clips with subtitles/data tracks are rejected; export them as a plain video first.
- Every track and the container must have a finite, positive duration of at most 120 seconds. Input frames must fit within 4,096 pixels per edge and 8,847,360 pixels total, at no more than 120 fps.
- FFmpeg normalizes rotation and produces H.264/yuv420p MP4 with optional AAC stereo audio, 30 fps, and a maximum 1,280-pixel edge. Smaller frames are not enlarged. `faststart` places playback metadata before media data. Original metadata, chapters, filenames, and unused tracks are discarded.
- Processing permits two encoders per backend instance. Probe operations have a 10-second deadline; encoding has a 90-second deadline and two worker threads. Busy or timed-out processing returns 503. Malformed or unsupported media returns 400; excessive size returns 413.
- Generated UUID filenames live in private `.local-videos`, ignored by Git and outside static routes. Override with `ZAZA_VIDEO_DIRECTORY`; use an absolute path for storage elsewhere. Upload bytes are staged temporarily and removed on completion or failure. Successful processed content is immutable.
- Encoding holds no database transaction/property lock. Access, property status, and the five-active-video capacity are checked before encoding and again under a lock before persistence. Failed persistence/audit rolls back the row and removes its newly stored file.
- `caption` is optional on upload, trimmed, and at most 200 characters. Caption and status changes increment the video version and append audit history.

Processing options follow the official [FFmpeg CLI](https://ffmpeg.org/ffmpeg.html), [FFprobe](https://ffmpeg.org/ffprobe.html), and [scale filter](https://ffmpeg.org/ffmpeg-filters.html#scale) documentation.

## Permissions and publication

Owners/admins of the owning organization or its currently delegated agency can upload, caption, and archive/restore videos. Authorized organization members can list videos and view active private content. Guests cannot read private routes; outsiders receive 404. Delegation revocation ends agency access. Archived properties reject uploads, caption edits, and restoration; archival remains possible.

Publishing uses the existing listing endpoint with optional ordered `videos` selections:

```json
{
  "version": 0,
  "propertyVersion": 1,
  "unitVersion": 0,
  "images": [],
  "videos": [{"id":"video-uuid","version":0}]
}
```

Select at most five unique active videos from the same property, each with its current version. Foreign/archived IDs return 404, stale versions return 409, and duplicate/invalid selections return 400. Omitting either media array clears that medium's previous selections: include current image selections when adding videos to an existing gallery.

Public listing responses include `videos` with the approved caption, type, dimensions, duration, ID, and relative content URL. Private storage keys/paths, property IDs, versions, and original names are excluded. Render captions as text.

A public URL only serves a selected video of a currently visible listing. Edits or archival of a selected image or video hide the entire offer and both media galleries until reviewed and republished. Restoration does not automatically publish. Unselected media changes do not hide an offer. Existing property/unit/delegation/withdrawal visibility checks also apply.

Video content uses `video/mp4`, `nosniff`, `no-store`, and byte-range support. GET with `Range: bytes=0-99` returns 206 with `Content-Range`; an out-of-bounds range returns 416. Authorization and current listing visibility are checked on every new request, including seeks. Configure the exact Flutter web origin in `ZAZA_CORS_ORIGINS`; Range is allowed and range response headers are exposed. Private browser playback must supply the bearer token (for example an authenticated fetch/client); a bare video URL does not carry it.

## Endpoints

Private base: `/api/v1/organizations/{organizationId}/properties/{propertyId}/videos`, requiring bearer authentication.

| Method | Suffix | Behavior |
| --- | --- | --- |
| POST | Empty | Multipart `file` and optional `caption`; private upload |
| GET | Empty | Metadata; `includeArchived`, limit, offset |
| GET | `/{videoId}/content` | Active private bytes with seeking |
| PUT | `/{videoId}` | Caption edit: `{version,caption}` |
| PATCH | `/{videoId}/status` | Archive/restore: `{version,status}` |

Guest GET `/api/v1/listings/{listingId}/videos/{videoId}` serves approved videos of visible listings.

## Verify and scope

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```

Install the video tools before running the suite, or provide both executable paths in process environment variables. The tests use the separate `zaza_foundation_test` schema, synthesize clips with real FFmpeg, and store generated media under ignored `.work` directories. They cover conversion/metadata removal, private and public byte ranges, permissions, publication/version rules, malformed/oversized/long/large-frame sources, capacity, concurrent edits, rollback/file cleanup, missing tools, deadlines, and permission changes during encoding.

This is synchronous local development storage. Flutter media screens/integration, background encoding queues, thumbnails, cloud object storage/CDN, multi-instance shared storage, hard crash recovery for abandoned temporary files, permanent retention cleanup, and additional formats remain follow-up work. Archived content is retained privately. Back up the database and media directories together.
