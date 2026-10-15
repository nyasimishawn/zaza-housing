ALTER TABLE rental_applications DROP CONSTRAINT rental_applications_status_check;
ALTER TABLE rental_applications ADD CONSTRAINT rental_applications_status_check CHECK (status IN
    ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED','REJECTED','WITHDRAWN','CANCELLED','LEASED','CLOSED'));
ALTER TABLE application_events DROP CONSTRAINT application_events_type_check;
ALTER TABLE application_events ADD CONSTRAINT application_events_type_check CHECK (type IN ('CREATED','DETAILS_UPDATED','LISTING_REFRESHED','SUBMITTED','REVIEW_STARTED',
    'INFORMATION_REQUESTED','APPROVED','REJECTED','WITHDRAWN','APPROVAL_CANCELLED','LEASE_SIGNED','LEASE_CLOSED'));
DROP INDEX applications_one_active_per_applicant_unit;
CREATE UNIQUE INDEX applications_one_active_per_applicant_unit ON rental_applications(applicant_id, unit_id)
    WHERE status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED','LEASED');
CREATE TABLE rental_leases (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    property_id UUID NOT NULL,
    unit_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES app_users(id),
    owner_organization_id UUID NOT NULL REFERENCES organizations(id),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','SIGNED','ACTIVE','COMPLETED','TERMINATED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    current_revision_id UUID,
    shared_revision_id UUID,
    unit_version BIGINT NOT NULL CHECK (unit_version >= 1),
    first_shared_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    unit_released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (property_id, unit_id, application_id) REFERENCES rental_applications(property_id, unit_id, id),
    CHECK (status = 'DRAFT' OR current_revision_id IS NOT NULL),
    CHECK (shared_revision_id IS NULL OR first_shared_at IS NOT NULL),
    CHECK (status <> 'CANCELLED' OR unit_released_at IS NOT NULL)
);
CREATE UNIQUE INDEX leases_one_unit_hold ON rental_leases(unit_id) WHERE unit_released_at IS NULL;
CREATE UNIQUE INDEX leases_one_application_hold ON rental_leases(application_id) WHERE unit_released_at IS NULL;
CREATE INDEX leases_property_idx ON rental_leases(property_id, updated_at DESC, id);
CREATE INDEX leases_tenant_idx ON rental_leases(tenant_id, updated_at DESC, id) WHERE first_shared_at IS NOT NULL;
CREATE TABLE lease_revisions (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES rental_leases(id),
    number INTEGER NOT NULL CHECK (number >= 1),
    terms JSONB NOT NULL CHECK (jsonb_typeof(terms) = 'object'),
    parties JSONB NOT NULL CHECK (jsonb_typeof(parties) = 'object'),
    document TEXT NOT NULL,
    document_hash CHAR(64) NOT NULL CHECK (document_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(lease_id, id), UNIQUE(lease_id, number)
);
ALTER TABLE rental_leases ADD FOREIGN KEY (id, current_revision_id) REFERENCES lease_revisions(lease_id, id);
ALTER TABLE rental_leases ADD FOREIGN KEY (id, shared_revision_id) REFERENCES lease_revisions(lease_id, id);
CREATE TABLE lease_revision_shares (
    revision_id UUID PRIMARY KEY REFERENCES lease_revisions(id),
    shared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE lease_signatures (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    party VARCHAR(10) NOT NULL CHECK (party IN ('TENANT','LESSOR')),
    signer_id UUID NOT NULL REFERENCES app_users(id),
    typed_name VARCHAR(150) NOT NULL,
    email VARCHAR(254) NOT NULL,
    document_hash CHAR(64) NOT NULL,
    method VARCHAR(50) NOT NULL CHECK (method = 'ACCOUNT_PASSWORD_CONSENT'),
    signed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (lease_id, revision_id) REFERENCES lease_revisions(lease_id, id),
    UNIQUE(revision_id, party), UNIQUE(revision_id, signer_id)
);
CREATE TABLE lease_terminations (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    requester_id UUID NOT NULL REFERENCES app_users(id),
    requester_party VARCHAR(10) NOT NULL CHECK (requester_party IN ('TENANT','LESSOR')),
    proposed_end_date DATE NOT NULL,
    message VARCHAR(2000) NOT NULL,
    document TEXT NOT NULL,
    document_hash CHAR(64) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (lease_id, revision_id) REFERENCES lease_revisions(lease_id, id)
);
CREATE UNIQUE INDEX lease_one_open_termination ON lease_terminations(lease_id) WHERE status IN ('PENDING','ACCEPTED');
CREATE TABLE lease_termination_consents (
    id UUID PRIMARY KEY,
    termination_id UUID NOT NULL REFERENCES lease_terminations(id),
    party VARCHAR(10) NOT NULL CHECK (party IN ('TENANT','LESSOR')),
    signer_id UUID NOT NULL REFERENCES app_users(id),
    typed_name VARCHAR(150) NOT NULL,
    email VARCHAR(254) NOT NULL,
    document_hash CHAR(64) NOT NULL,
    method VARCHAR(50) NOT NULL CHECK (method = 'ACCOUNT_PASSWORD_CONSENT'),
    signed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(termination_id, party), UNIQUE(termination_id, signer_id)
);
CREATE TABLE lease_events (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES rental_leases(id),
    revision_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    actor_party VARCHAR(10) NOT NULL CHECK (actor_party IN ('TENANT','LESSOR')),
    message VARCHAR(2000) NOT NULL,
    tenant_visible BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (lease_id, revision_id) REFERENCES lease_revisions(lease_id, id)
);
CREATE INDEX lease_events_time_idx ON lease_events(lease_id, created_at, id);
CREATE TRIGGER lease_revisions_immutable BEFORE UPDATE OR DELETE ON lease_revisions FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_revisions_no_truncate BEFORE TRUNCATE ON lease_revisions FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_shares_immutable BEFORE UPDATE OR DELETE ON lease_revision_shares FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_signatures_immutable BEFORE UPDATE OR DELETE ON lease_signatures FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_signatures_no_truncate BEFORE TRUNCATE ON lease_signatures FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_termination_consents_immutable BEFORE UPDATE OR DELETE ON lease_termination_consents FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_termination_consents_no_truncate BEFORE TRUNCATE ON lease_termination_consents FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_events_immutable BEFORE UPDATE OR DELETE ON lease_events FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_events_no_truncate BEFORE TRUNCATE ON lease_events FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
ALTER TABLE in_app_notifications ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE in_app_notifications ADD COLUMN lease_id UUID REFERENCES rental_leases(id);
ALTER TABLE in_app_notifications ADD COLUMN lease_event_id UUID REFERENCES lease_events(id);
ALTER TABLE in_app_notifications ADD COLUMN lease_url VARCHAR(250);
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK
    ((event_id IS NOT NULL AND lease_event_id IS NULL AND lease_id IS NULL) OR
     (event_id IS NULL AND lease_event_id IS NOT NULL AND lease_id IS NOT NULL AND lease_url IS NOT NULL));
CREATE UNIQUE INDEX notification_lease_event_unique ON in_app_notifications(user_id, lease_event_id);

CREATE TRIGGER lease_shares_no_truncate BEFORE TRUNCATE ON lease_revision_shares FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE FUNCTION protect_lease_termination_proposal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.lease_id,OLD.revision_id,OLD.requester_id,OLD.requester_party,OLD.proposed_end_date,OLD.message,OLD.document,OLD.document_hash,OLD.created_at)
        IS DISTINCT FROM (NEW.id,NEW.lease_id,NEW.revision_id,NEW.requester_id,NEW.requester_party,NEW.proposed_end_date,NEW.message,NEW.document,NEW.document_hash,NEW.created_at)
        OR OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Termination proposal content and resolved decisions are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lease_terminations_protect BEFORE UPDATE ON lease_terminations FOR EACH ROW EXECUTE FUNCTION protect_lease_termination_proposal();
CREATE TRIGGER lease_terminations_no_delete BEFORE DELETE ON lease_terminations FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_terminations_no_truncate BEFORE TRUNCATE ON lease_terminations FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
