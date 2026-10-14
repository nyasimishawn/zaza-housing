CREATE TABLE property_images (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id),
    storage_key VARCHAR(50) NOT NULL UNIQUE,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 1600),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 1600),
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
    alt_text VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(property_id, id)
);
CREATE INDEX images_property_idx ON property_images(property_id, created_at, id);
ALTER TABLE rental_listings ADD CONSTRAINT listings_property_id_unique UNIQUE(property_id, id);
CREATE TABLE listing_image_references (
    listing_id UUID NOT NULL,
    property_id UUID NOT NULL,
    image_id UUID NOT NULL,
    image_version BIGINT NOT NULL CHECK (image_version >= 0),
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 19),
    PRIMARY KEY (listing_id, image_id),
    UNIQUE(listing_id, position),
    FOREIGN KEY (property_id, listing_id) REFERENCES rental_listings(property_id, id),
    FOREIGN KEY (property_id, image_id) REFERENCES property_images(property_id, id)
);
