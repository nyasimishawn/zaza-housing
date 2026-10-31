-- Additive media capabilities. Existing scan versions remain one-page documents.
ALTER TABLE vault_document_versions DROP CONSTRAINT vault_document_versions_content_type_check;
ALTER TABLE vault_document_versions ADD CONSTRAINT vault_document_versions_content_type_check
    CHECK (content_type IN ('image/png','image/jpeg','application/pdf'));
ALTER TABLE vault_document_versions DROP CONSTRAINT vault_document_versions_byte_size_check;
ALTER TABLE vault_document_versions ADD CONSTRAINT vault_document_versions_byte_size_check
    CHECK (byte_size > 0 AND byte_size <= CASE WHEN content_type='application/pdf' THEN 20971520 ELSE 10485760 END);
ALTER TABLE vault_document_versions ADD COLUMN page_count INTEGER NOT NULL DEFAULT 1
    CHECK (page_count BETWEEN 1 AND 20);
ALTER TABLE vault_document_versions ADD CONSTRAINT vault_document_versions_scan_page_count_check
    CHECK (content_type='application/pdf' OR page_count=1);

ALTER TABLE property_videos ADD COLUMN content_hash CHAR(64);
ALTER TABLE property_videos ADD COLUMN poster_hash CHAR(64);
ALTER TABLE property_videos ADD COLUMN poster_size BIGINT;
ALTER TABLE property_videos ADD CONSTRAINT property_video_media_hashes CHECK (
    (content_hash IS NULL OR content_hash ~ '^[a-f0-9]{64}$') AND
    ((poster_hash IS NULL AND poster_size IS NULL) OR
      (poster_hash ~ '^[a-f0-9]{64}$' AND poster_size BETWEEN 1 AND 2097152)));

CREATE TABLE property_video_uploads (
    id UUID PRIMARY KEY, property_id UUID NOT NULL REFERENCES properties(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    actor_id UUID NOT NULL REFERENCES app_users(id), request_id UUID NOT NULL,
    property_version BIGINT NOT NULL CHECK(property_version>=0),
    caption VARCHAR(200) NOT NULL, source_key VARCHAR(100) NOT NULL UNIQUE,
    source_hash CHAR(64) NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
    source_size BIGINT NOT NULL CHECK(source_size BETWEEN 1 AND 52428800),
    source_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','PROCESSING','READY','FAILED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
    progress INTEGER NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 100),
    failure_code VARCHAR(40), video_id UUID REFERENCES property_videos(id), lease_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
    UNIQUE(actor_id,property_id,request_id),
    CHECK((status='READY')=(video_id IS NOT NULL))
);
CREATE INDEX property_video_upload_queue ON property_video_uploads(created_at,id) WHERE status='QUEUED';
CREATE INDEX property_video_upload_property ON property_video_uploads(property_id,created_at DESC,id);
CREATE INDEX property_video_upload_actor_quota ON property_video_uploads(actor_id,created_at);
