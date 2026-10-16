CREATE TABLE lease_inspection_policies (
    lease_id UUID PRIMARY KEY REFERENCES rental_leases(id),
    move_in_required BOOLEAN NOT NULL,
    enabled_by UUID NOT NULL REFERENCES app_users(id),
    enabled_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE lease_inspections (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES lease_inspection_policies(lease_id),
    kind VARCHAR(10) NOT NULL CHECK(kind IN ('MOVE_IN','MOVE_OUT')),
    status VARCHAR(20) NOT NULL CHECK(status IN ('DRAFT','AWAITING_TENANT','DISPUTED','ACCEPTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    current_revision_id UUID NOT NULL,
    shared_revision_id UUID,
    accepted_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(lease_id,kind), UNIQUE(id,lease_id),
    CHECK ((status='ACCEPTED')=(accepted_revision_id IS NOT NULL)),
    CHECK (accepted_revision_id IS NULL OR (accepted_revision_id=shared_revision_id AND shared_revision_id=current_revision_id)),
    CHECK (status='DRAFT' OR shared_revision_id IS NOT NULL)
);
CREATE TABLE lease_inspection_revisions (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES lease_inspections(id),
    number INT NOT NULL CHECK(number>0),
    details JSONB NOT NULL CHECK(jsonb_typeof(details)='object'),
    currency CHAR(3) NOT NULL,
    document TEXT NOT NULL,
    document_hash CHAR(64) NOT NULL CHECK(document_hash~'^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(inspection_id,id), UNIQUE(inspection_id,number)
);
ALTER TABLE lease_inspections ADD FOREIGN KEY(id,current_revision_id) REFERENCES lease_inspection_revisions(inspection_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE lease_inspections ADD FOREIGN KEY(id,shared_revision_id) REFERENCES lease_inspection_revisions(inspection_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE lease_inspections ADD FOREIGN KEY(id,accepted_revision_id) REFERENCES lease_inspection_revisions(inspection_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE lease_inspection_shares (
    inspection_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    document_hash CHAR(64) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    method VARCHAR(40) NOT NULL CHECK(method='ACCOUNT_PASSWORD_CONSENT'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(inspection_id,revision_id),
    FOREIGN KEY(inspection_id,revision_id) REFERENCES lease_inspection_revisions(inspection_id,id)
);
CREATE TABLE lease_inspection_decisions (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    decision VARCHAR(10) NOT NULL CHECK(decision IN ('ACCEPT','DISPUTE')),
    document_hash CHAR(64) NOT NULL,
    vacancy_confirmed BOOLEAN NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    item_keys JSONB NOT NULL CHECK(jsonb_typeof(item_keys)='array'),
    method VARCHAR(40) NOT NULL CHECK(method IN ('ACCOUNT_PASSWORD_CONSENT','ACCOUNT_REQUEST')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(inspection_id,revision_id),
    FOREIGN KEY(inspection_id,revision_id) REFERENCES lease_inspection_shares(inspection_id,revision_id)
);
CREATE TABLE lease_inspection_evidence (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES lease_inspections(id),
    uploader_id UUID NOT NULL REFERENCES app_users(id),
    party VARCHAR(10) NOT NULL CHECK(party IN ('TENANT','LESSOR')),
    caption VARCHAR(200) NOT NULL,
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    content_type VARCHAR(20) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
    width INT NOT NULL CHECK(width>0), height INT NOT NULL CHECK(height>0),
    byte_size BIGINT NOT NULL CHECK(byte_size>0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(inspection_id,id)
);
CREATE TABLE lease_inspection_evidence_publications (
    inspection_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    PRIMARY KEY(inspection_id,revision_id,evidence_id),
    FOREIGN KEY(inspection_id,revision_id) REFERENCES lease_inspection_shares(inspection_id,revision_id),
    FOREIGN KEY(inspection_id,evidence_id) REFERENCES lease_inspection_evidence(inspection_id,id)
);
CREATE TABLE lease_inspection_operations (
    lease_id UUID NOT NULL REFERENCES rental_leases(id),
    request_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    action VARCHAR(40) NOT NULL,
    target_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(lease_id,request_id)
);
CREATE TABLE lease_inspection_events (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES rental_leases(id),
    inspection_id UUID,
    revision_id UUID,
    action VARCHAR(40) NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    party VARCHAR(10) NOT NULL CHECK(party IN ('LESSOR','TENANT')),
    reason VARCHAR(2000) NOT NULL,
    item_keys JSONB NOT NULL CHECK(jsonb_typeof(item_keys)='array'),
    tenant_visible BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(inspection_id,lease_id) REFERENCES lease_inspections(id,lease_id),
    FOREIGN KEY(inspection_id,revision_id) REFERENCES lease_inspection_revisions(inspection_id,id)
);
CREATE INDEX inspection_events_history ON lease_inspection_events(lease_id,created_at,id);
CREATE INDEX inspection_evidence_time ON lease_inspection_evidence(inspection_id,created_at,id);
CREATE INDEX inspection_evidence_shared ON lease_inspection_evidence_publications(evidence_id);
CREATE FUNCTION protect_lease_inspection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.lease_id,OLD.kind,OLD.created_at) IS DISTINCT FROM (NEW.id,NEW.lease_id,NEW.kind,NEW.created_at) OR OLD.status='ACCEPTED' THEN
        RAISE EXCEPTION 'Inspection identities and accepted reports are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inspection_identity BEFORE UPDATE ON lease_inspections FOR EACH ROW EXECUTE FUNCTION protect_lease_inspection();
CREATE TRIGGER inspections_no_delete BEFORE DELETE ON lease_inspections FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER inspections_no_truncate BEFORE TRUNCATE ON lease_inspections FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['lease_inspection_policies','lease_inspection_revisions','lease_inspection_shares','lease_inspection_decisions','lease_inspection_evidence','lease_inspection_evidence_publications','lease_inspection_operations','lease_inspection_events'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_no_truncate',t);
    END LOOP;
END $$;
