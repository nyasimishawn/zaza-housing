ALTER TABLE property_units ADD CONSTRAINT units_property_id_unique UNIQUE(property_id, id);

CREATE TABLE rental_listings (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    unit_id UUID NOT NULL UNIQUE,
    title VARCHAR(150) NOT NULL CHECK (length(trim(title)) > 0),
    description VARCHAR(5000) NOT NULL CHECK (length(trim(description)) > 0),
    show_exact_location BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    publisher_organization_id UUID REFERENCES organizations(id),
    source_property_version BIGINT CHECK (source_property_version >= 0),
    source_unit_version BIGINT CHECK (source_unit_version >= 0),
    public_snapshot JSONB CHECK (jsonb_typeof(public_snapshot) = 'object'),
    published_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_document TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', title || ' ' || description || ' ' || coalesce(public_snapshot ->> 'city', ''))
    ) STORED,
    FOREIGN KEY (property_id, unit_id) REFERENCES property_units(property_id, id),
    CHECK (status <> 'PUBLISHED' OR (publisher_organization_id IS NOT NULL AND public_snapshot IS NOT NULL
        AND source_property_version IS NOT NULL AND source_unit_version IS NOT NULL AND published_at IS NOT NULL))
);
CREATE INDEX listings_property_idx ON rental_listings(property_id, created_at, id);
CREATE INDEX listings_search_idx ON rental_listings USING gin(search_document) WHERE status = 'PUBLISHED';
CREATE INDEX listings_recent_idx ON rental_listings(published_at DESC, id) WHERE status = 'PUBLISHED';
CREATE INDEX listings_location_idx ON rental_listings((public_snapshot ->> 'countryCode'),
    lower(public_snapshot ->> 'city'), (public_snapshot ->> 'propertyType')) WHERE status = 'PUBLISHED';
CREATE INDEX listings_price_idx ON rental_listings((public_snapshot ->> 'currency'),
    ((public_snapshot ->> 'monthlyRent')::numeric), id) WHERE status = 'PUBLISHED';
