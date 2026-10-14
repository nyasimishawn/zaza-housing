CREATE TABLE property_videos (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id),
    storage_key VARCHAR(50) NOT NULL UNIQUE,
    content_type VARCHAR(20) NOT NULL DEFAULT 'video/mp4' CHECK (content_type = 'video/mp4'),
    width INTEGER NOT NULL CHECK (width BETWEEN 2 AND 1280 AND width % 2 = 0),
    height INTEGER NOT NULL CHECK (height BETWEEN 2 AND 1280 AND height % 2 = 0),
    duration_seconds DOUBLE PRECISION NOT NULL CHECK (duration_seconds > 0 AND duration_seconds <= 120.05),
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 1 AND 52428800),
    caption VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(property_id, id)
);
CREATE INDEX videos_property_idx ON property_videos(property_id, created_at, id);
CREATE TABLE listing_video_references (
    listing_id UUID NOT NULL,
    property_id UUID NOT NULL,
    video_id UUID NOT NULL,
    video_version BIGINT NOT NULL CHECK (video_version >= 0),
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 4),
    PRIMARY KEY (listing_id, video_id),
    UNIQUE(listing_id, position),
    FOREIGN KEY (property_id, listing_id) REFERENCES rental_listings(property_id, id),
    FOREIGN KEY (property_id, video_id) REFERENCES property_videos(property_id, id)
);
