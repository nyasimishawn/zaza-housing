-- Business-account ownership is distinct from the agency delegated to manage a property.
CREATE TABLE properties (
    id UUID PRIMARY KEY,
    owner_organization_id UUID NOT NULL REFERENCES organizations(id),
    manager_organization_id UUID REFERENCES organizations(id),
    name VARCHAR(150) NOT NULL CHECK (length(trim(name)) > 0),
    description VARCHAR(5000) NOT NULL DEFAULT '',
    type VARCHAR(30) NOT NULL CHECK (type IN ('APARTMENT', 'HOUSE', 'COMMERCIAL', 'LAND', 'MIXED_USE')),
    country_code CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    city VARCHAR(100) NOT NULL CHECK (length(trim(city)) > 0),
    address VARCHAR(250) NOT NULL CHECK (length(trim(address)) > 0),
    latitude NUMERIC(10,7) CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(10,7) CHECK (longitude BETWEEN -180 AND 180),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (manager_organization_id IS NULL OR manager_organization_id <> owner_organization_id),
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX properties_owner_idx ON properties(owner_organization_id, created_at, id);
CREATE INDEX properties_manager_idx ON properties(manager_organization_id, created_at, id)
    WHERE manager_organization_id IS NOT NULL;

CREATE TABLE property_units (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id),
    label VARCHAR(100) NOT NULL CHECK (length(trim(label)) > 0),
    bedrooms INTEGER NOT NULL CHECK (bedrooms BETWEEN 0 AND 100),
    bathrooms INTEGER NOT NULL CHECK (bathrooms BETWEEN 0 AND 100),
    floor_area_sqm NUMERIC(12,2) CHECK (floor_area_sqm > 0),
    monthly_rent NUMERIC(14,2) NOT NULL CHECK (monthly_rent >= 0),
    deposit NUMERIC(14,2) NOT NULL CHECK (deposit >= 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    availability VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (availability IN ('AVAILABLE', 'RESERVED', 'OCCUPIED', 'UNAVAILABLE')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Retain labels across archival so restoring a unit never creates a duplicate.
CREATE UNIQUE INDEX units_property_label_idx ON property_units(property_id, lower(label));
CREATE INDEX units_property_idx ON property_units(property_id, created_at, id);
