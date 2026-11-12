CREATE TABLE property_coowners (
 property_id UUID NOT NULL REFERENCES properties(id), organization_id UUID NOT NULL REFERENCES organizations(id),
 share_percent NUMERIC(5,2) NOT NULL CHECK(share_percent>0 AND share_percent<100),
 PRIMARY KEY(property_id,organization_id)
);
CREATE TABLE property_ownership_proposals (
 id UUID PRIMARY KEY,property_id UUID NOT NULL REFERENCES properties(id),property_name VARCHAR(150) NOT NULL,
 from_organization_id UUID NOT NULL REFERENCES organizations(id),to_organization_id UUID NOT NULL REFERENCES organizations(id),
 kind VARCHAR(15) NOT NULL CHECK(kind IN ('COOWNER','TRANSFER')),share_percent NUMERIC(5,2) NOT NULL CHECK(share_percent>=0 AND share_percent<=100),
 property_version BIGINT NOT NULL,requester_id UUID NOT NULL REFERENCES app_users(id),
 document TEXT NOT NULL,document_hash CHAR(64) NOT NULL,reason VARCHAR(2000) NOT NULL,
 status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), CHECK(from_organization_id<>to_organization_id)
);
CREATE UNIQUE INDEX ownership_one_pending ON property_ownership_proposals(property_id) WHERE status='PENDING';
CREATE TABLE property_ownership_consents (
 id UUID PRIMARY KEY,proposal_id UUID NOT NULL REFERENCES property_ownership_proposals(id),
 organization_id UUID NOT NULL REFERENCES organizations(id),signer_id UUID NOT NULL REFERENCES app_users(id),
 typed_name VARCHAR(150) NOT NULL,email VARCHAR(254) NOT NULL,document_hash CHAR(64) NOT NULL,
 method VARCHAR(50) NOT NULL DEFAULT 'ACCOUNT_PASSWORD_CONSENT',signed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(proposal_id,organization_id),UNIQUE(proposal_id,signer_id)
);
CREATE FUNCTION protect_ownership_proposal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (to_jsonb(OLD)-'status') IS DISTINCT FROM (to_jsonb(NEW)-'status') OR OLD.status<>'PENDING' THEN
  RAISE EXCEPTION 'Ownership proposal evidence and resolved decisions are immutable';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER ownership_proposals_protect BEFORE UPDATE ON property_ownership_proposals FOR EACH ROW EXECUTE FUNCTION protect_ownership_proposal();
CREATE TRIGGER ownership_proposals_retain BEFORE DELETE OR TRUNCATE ON property_ownership_proposals FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER ownership_consents_immutable BEFORE UPDATE OR DELETE ON property_ownership_consents FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER ownership_consents_retain BEFORE TRUNCATE ON property_ownership_consents FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
