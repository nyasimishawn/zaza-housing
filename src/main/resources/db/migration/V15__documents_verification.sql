CREATE TABLE vault_documents (
    id UUID PRIMARY KEY,
    owner_user_id UUID REFERENCES app_users(id), organization_id UUID REFERENCES organizations(id),
    category VARCHAR(30) NOT NULL CHECK(category IN ('IDENTITY','BUSINESS_REGISTRATION','PROPERTY_OWNERSHIP','LEASE','RECEIPT','INVOICE','INSPECTION','SERVICE','OTHER')),
    title VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED','PURGE_PENDING','PURGED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), current_version_id UUID NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CHECK((owner_user_id IS NULL)<>(organization_id IS NULL))
);
CREATE INDEX vault_documents_personal ON vault_documents(owner_user_id,updated_at DESC,id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX vault_documents_organization ON vault_documents(organization_id,updated_at DESC,id) WHERE organization_id IS NOT NULL;
CREATE TABLE vault_document_versions (
    id UUID PRIMARY KEY, document_id UUID NOT NULL REFERENCES vault_documents(id), number INT NOT NULL CHECK(number BETWEEN 1 AND 20),
    storage_key VARCHAR(100) NOT NULL UNIQUE, key_id VARCHAR(40) NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
    content_hash CHAR(64) NOT NULL CHECK(content_hash~'^[a-f0-9]{64}$'),
    width INT NOT NULL CHECK(width>0), height INT NOT NULL CHECK(height>0), byte_size BIGINT NOT NULL CHECK(byte_size>0 AND byte_size<=10485760),
    cipher_size BIGINT NOT NULL CHECK(cipher_size>byte_size),
    created_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(document_id,id), UNIQUE(document_id,number)
);
ALTER TABLE vault_documents ADD FOREIGN KEY(id,current_version_id) REFERENCES vault_document_versions(document_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE vault_document_grants (
    id UUID PRIMARY KEY, document_id UUID NOT NULL, document_version_id UUID NOT NULL,
    application_id UUID NOT NULL REFERENCES rental_applications(id),
    purpose VARCHAR(40) NOT NULL CHECK(purpose='APPLICATION_SCREENING'),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(document_id,document_version_id) REFERENCES vault_document_versions(document_id,id)
);
CREATE UNIQUE INDEX vault_grants_one_current ON vault_document_grants(application_id,document_version_id) WHERE revoked_at IS NULL;
CREATE INDEX vault_grants_application ON vault_document_grants(application_id,created_at,id);
CREATE INDEX vault_grants_document ON vault_document_grants(document_id);
CREATE TABLE verification_cases (
    id UUID PRIMARY KEY, owner_user_id UUID REFERENCES app_users(id), organization_id UUID REFERENCES organizations(id),
    kind VARCHAR(10) NOT NULL CHECK(kind IN ('IDENTITY','BUSINESS')),
    status VARCHAR(20) NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED','REJECTED','WITHDRAWN','REVOKED','EXPIRED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), current_submission_id UUID NOT NULL, shared_submission_id UUID,
    assigned_to UUID REFERENCES app_users(id), valid_until TIMESTAMPTZ, resolution VARCHAR(2000),
    created_by UUID NOT NULL REFERENCES app_users(id), submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((kind='IDENTITY' AND owner_user_id IS NOT NULL AND organization_id IS NULL) OR (kind='BUSINESS' AND owner_user_id IS NULL AND organization_id IS NOT NULL)),
    CHECK ((status='UNDER_REVIEW')=(assigned_to IS NOT NULL)),
    CHECK (status NOT IN ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED','REJECTED','REVOKED','EXPIRED') OR (shared_submission_id IS NOT NULL AND submitted_at IS NOT NULL)),
    CHECK (status NOT IN ('VERIFIED','REVOKED','EXPIRED') OR valid_until IS NOT NULL)
);
CREATE UNIQUE INDEX verification_active_personal ON verification_cases(owner_user_id) WHERE status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED');
CREATE UNIQUE INDEX verification_active_business ON verification_cases(organization_id) WHERE status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED');
CREATE INDEX verification_queue ON verification_cases(status,updated_at,id);
CREATE TABLE verification_submissions (
    id UUID PRIMARY KEY, case_id UUID NOT NULL REFERENCES verification_cases(id), number INT NOT NULL CHECK(number BETWEEN 1 AND 20),
    details JSONB NOT NULL CHECK(jsonb_typeof(details)='object'),
    prepared_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(case_id,id), UNIQUE(case_id,number)
);
ALTER TABLE verification_cases ADD FOREIGN KEY(id,current_submission_id) REFERENCES verification_submissions(case_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE verification_cases ADD FOREIGN KEY(id,shared_submission_id) REFERENCES verification_submissions(case_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE verification_submission_documents (
    case_id UUID NOT NULL, submission_id UUID NOT NULL, document_id UUID NOT NULL, document_version_id UUID NOT NULL,
    position INT NOT NULL CHECK(position BETWEEN 1 AND 10),
    PRIMARY KEY(case_id,submission_id,document_version_id), UNIQUE(case_id,submission_id,position),
    FOREIGN KEY(case_id,submission_id) REFERENCES verification_submissions(case_id,id),
    FOREIGN KEY(document_id,document_version_id) REFERENCES vault_document_versions(document_id,id)
);
CREATE TABLE verification_submission_shares (
    case_id UUID NOT NULL, submission_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id), method VARCHAR(40) NOT NULL CHECK(method='ACCOUNT_PASSWORD_CONSENT'), created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(case_id,submission_id), FOREIGN KEY(case_id,submission_id) REFERENCES verification_submissions(case_id,id)
);
CREATE TABLE vault_operations (
    scope_key VARCHAR(60) NOT NULL, request_id UUID NOT NULL, actor_id UUID NOT NULL REFERENCES app_users(id),
    action VARCHAR(40) NOT NULL, target_id UUID NOT NULL, fingerprint CHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(scope_key,request_id)
);
CREATE INDEX vault_upload_quota ON vault_operations(scope_key,created_at) WHERE action IN ('DOCUMENT_UPLOADED','DOCUMENT_VERSION_ADDED');
CREATE TABLE vault_events (
    id UUID PRIMARY KEY, document_id UUID REFERENCES vault_documents(id), case_id UUID REFERENCES verification_cases(id),
    action VARCHAR(40) NOT NULL, actor_id UUID NOT NULL REFERENCES app_users(id), party VARCHAR(20) NOT NULL,
    reason VARCHAR(2000) NOT NULL, reviewer_visible BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    CHECK((document_id IS NULL)<>(case_id IS NULL))
);
CREATE INDEX vault_document_history ON vault_events(document_id,created_at,id) WHERE document_id IS NOT NULL;
CREATE INDEX verification_history ON vault_events(case_id,created_at,id) WHERE case_id IS NOT NULL;
CREATE TABLE vault_purge_tasks (
    document_id UUID NOT NULL, document_version_id UUID NOT NULL, completed_at TIMESTAMPTZ,
    PRIMARY KEY(document_id,document_version_id),
    FOREIGN KEY(document_id,document_version_id) REFERENCES vault_document_versions(document_id,id)
);
CREATE FUNCTION validate_verification_document_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM verification_cases c JOIN vault_documents d ON d.id=NEW.document_id
        WHERE c.id=NEW.case_id AND c.owner_user_id IS NOT DISTINCT FROM d.owner_user_id AND c.organization_id IS NOT DISTINCT FROM d.organization_id) THEN
        RAISE EXCEPTION 'Verification evidence must belong to the verified subject';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER verification_evidence_owner BEFORE INSERT ON verification_submission_documents FOR EACH ROW EXECUTE FUNCTION validate_verification_document_owner();
CREATE FUNCTION protect_vault_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.owner_user_id,OLD.organization_id,OLD.category,OLD.title,OLD.created_by,OLD.created_at) IS DISTINCT FROM
       (NEW.id,NEW.owner_user_id,NEW.organization_id,NEW.category,NEW.title,NEW.created_by,NEW.created_at)
       OR NEW.retain_until<OLD.retain_until OR OLD.status='PURGED'
       OR (OLD.status='PURGE_PENDING' AND NEW.status<>'PURGED') THEN
        RAISE EXCEPTION 'Document identity, retention and completed purge are protected';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vault_document_identity BEFORE UPDATE ON vault_documents FOR EACH ROW EXECUTE FUNCTION protect_vault_document();
CREATE FUNCTION protect_document_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.document_id,OLD.document_version_id,OLD.application_id,OLD.purpose,OLD.expires_at,OLD.created_by,OLD.created_at) IS DISTINCT FROM
       (NEW.id,NEW.document_id,NEW.document_version_id,NEW.application_id,NEW.purpose,NEW.expires_at,NEW.created_by,NEW.created_at) OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'Document grants are immutable except for first revocation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vault_grant_identity BEFORE UPDATE ON vault_document_grants FOR EACH ROW EXECUTE FUNCTION protect_document_grant();
CREATE FUNCTION protect_verification_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.owner_user_id,OLD.organization_id,OLD.kind,OLD.created_by,OLD.created_at) IS DISTINCT FROM
       (NEW.id,NEW.owner_user_id,NEW.organization_id,NEW.kind,NEW.created_by,NEW.created_at)
       OR OLD.status IN ('REJECTED','WITHDRAWN','REVOKED','EXPIRED')
       OR (OLD.status='VERIFIED' AND (NEW.status NOT IN ('WITHDRAWN','REVOKED','EXPIRED') OR NEW.valid_until IS DISTINCT FROM OLD.valid_until OR NEW.shared_submission_id IS DISTINCT FROM OLD.shared_submission_id)) THEN
        RAISE EXCEPTION 'Verification subjects, submitted evidence and terminal decisions are protected';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER verification_case_identity BEFORE UPDATE ON verification_cases FOR EACH ROW EXECUTE FUNCTION protect_verification_case();
CREATE FUNCTION protect_purge_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.document_id,OLD.document_version_id) IS DISTINCT FROM (NEW.document_id,NEW.document_version_id) OR OLD.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Purge task identities and completion evidence are protected';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vault_purge_task_identity BEFORE UPDATE ON vault_purge_tasks FOR EACH ROW EXECUTE FUNCTION protect_purge_task();
DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['vault_documents','vault_document_grants','verification_cases','vault_purge_tasks'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t||'_no_delete',t);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_no_truncate',t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['vault_document_versions','verification_submissions','verification_submission_documents','verification_submission_shares','vault_operations','vault_events'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_no_truncate',t);
    END LOOP;
END $$;
ALTER TABLE in_app_notifications ADD COLUMN vault_event_id UUID REFERENCES vault_events(id);
ALTER TABLE in_app_notifications ADD COLUMN vault_url VARCHAR(250);
ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK (
(vault_event_id IS NULL AND vault_url IS NULL AND (
 (conversation_event_id IS NOT NULL AND conversation_id IS NOT NULL AND conversation_url IS NOT NULL
 AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND work_event_id IS NULL AND service_finance_event_id IS NULL
 AND application_id IS NULL AND lease_id IS NULL AND work_request_id IS NULL AND service_payment_account_id IS NULL) OR
 (conversation_event_id IS NULL AND conversation_id IS NULL AND conversation_url IS NULL AND (
 (service_finance_event_id IS NOT NULL AND service_payment_account_id IS NOT NULL AND service_payment_url IS NOT NULL AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND work_event_id IS NULL AND application_id IS NULL AND lease_id IS NULL AND work_request_id IS NULL) OR
 (service_finance_event_id IS NULL AND service_payment_account_id IS NULL AND service_payment_url IS NULL AND
 ((work_event_id IS NOT NULL AND work_request_id IS NOT NULL AND work_request_url IS NOT NULL AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND application_id IS NULL AND lease_id IS NULL) OR
 (work_event_id IS NULL AND work_request_id IS NULL AND application_id IS NOT NULL AND
 ((event_id IS NOT NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND lease_id IS NULL) OR
 (event_id IS NULL AND lease_id IS NOT NULL AND lease_url IS NOT NULL AND ((lease_event_id IS NOT NULL AND billing_event_id IS NULL) OR (lease_event_id IS NULL AND billing_event_id IS NOT NULL)))))))))
))
 OR (vault_event_id IS NOT NULL AND vault_url IS NOT NULL AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND work_event_id IS NULL AND service_finance_event_id IS NULL AND conversation_event_id IS NULL AND application_id IS NULL AND lease_id IS NULL AND work_request_id IS NULL AND service_payment_account_id IS NULL AND conversation_id IS NULL AND lease_url IS NULL AND work_request_url IS NULL AND service_payment_url IS NULL AND conversation_url IS NULL)
);
CREATE UNIQUE INDEX notification_vault_unique ON in_app_notifications(user_id,vault_event_id);
