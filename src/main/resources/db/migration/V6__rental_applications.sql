CREATE TABLE tenant_profiles (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    details JSONB NOT NULL CHECK (jsonb_typeof(details) = 'object'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE rental_listings ADD CONSTRAINT listings_property_unit_id_unique UNIQUE(property_id, unit_id, id);
CREATE TABLE rental_applications (
    id UUID PRIMARY KEY,
    applicant_id UUID NOT NULL REFERENCES app_users(id),
    property_id UUID NOT NULL,
    unit_id UUID NOT NULL,
    listing_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN
        ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED','REJECTED','WITHDRAWN','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    listing_version BIGINT NOT NULL CHECK (listing_version >= 0),
    property_version BIGINT NOT NULL CHECK (property_version >= 0),
    unit_version BIGINT NOT NULL CHECK (unit_version >= 0),
    profile_version BIGINT NOT NULL CHECK (profile_version >= 0),
    details JSONB NOT NULL CHECK (jsonb_typeof(details) = 'object'),
    applicant_snapshot JSONB NOT NULL CHECK (jsonb_typeof(applicant_snapshot) = 'object'),
    listing_snapshot JSONB NOT NULL CHECK (jsonb_typeof(listing_snapshot) = 'object'),
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(property_id, unit_id, id),
    FOREIGN KEY (property_id, unit_id) REFERENCES property_units(property_id, id),
    FOREIGN KEY (property_id, unit_id, listing_id) REFERENCES rental_listings(property_id, unit_id, id),
    CHECK (status IN ('DRAFT','WITHDRAWN') OR submitted_at IS NOT NULL)
);
CREATE UNIQUE INDEX applications_one_active_per_applicant_unit ON rental_applications(applicant_id, unit_id)
    WHERE status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED');
CREATE INDEX applications_applicant_idx ON rental_applications(applicant_id, updated_at DESC, id);
CREATE INDEX applications_review_idx ON rental_applications(property_id, status, updated_at DESC, id) WHERE submitted_at IS NOT NULL;
CREATE TABLE application_unit_reservations (
    application_id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    unit_id UUID NOT NULL UNIQUE,
    reserved_unit_version BIGINT NOT NULL CHECK (reserved_unit_version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (property_id, unit_id, application_id) REFERENCES rental_applications(property_id, unit_id, id),
    FOREIGN KEY (property_id, unit_id) REFERENCES property_units(property_id, id)
);
CREATE TABLE application_events (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES rental_applications(id),
    type VARCHAR(40) NOT NULL CHECK (type IN ('CREATED','DETAILS_UPDATED','LISTING_REFRESHED','SUBMITTED','REVIEW_STARTED',
        'INFORMATION_REQUESTED','APPROVED','REJECTED','WITHDRAWN','APPROVAL_CANCELLED')),
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    actor_type VARCHAR(20) NOT NULL CHECK (actor_type IN ('APPLICANT','REVIEWER')),
    message VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX application_events_time_idx ON application_events(application_id, created_at, id);
CREATE TABLE application_reviewer_notes (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES rental_applications(id),
    author_id UUID NOT NULL REFERENCES app_users(id),
    text VARCHAR(2000) NOT NULL CHECK (length(trim(text)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX application_notes_time_idx ON application_reviewer_notes(application_id, created_at, id);
CREATE FUNCTION reject_application_history_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Application history is append-only';
END;
$$;
CREATE TRIGGER application_events_append_only BEFORE UPDATE OR DELETE ON application_events
    FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER application_events_no_truncate BEFORE TRUNCATE ON application_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER application_notes_append_only BEFORE UPDATE OR DELETE ON application_reviewer_notes
    FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER application_notes_no_truncate BEFORE TRUNCATE ON application_reviewer_notes
    FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    application_id UUID NOT NULL REFERENCES rental_applications(id),
    event_id UUID NOT NULL REFERENCES application_events(id),
    type VARCHAR(40) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    read_at TIMESTAMPTZ,
    UNIQUE(user_id, event_id)
);
CREATE INDEX notifications_user_idx ON in_app_notifications(user_id, created_at DESC, id);
CREATE INDEX notifications_unread_idx ON in_app_notifications(user_id) WHERE read_at IS NULL;
