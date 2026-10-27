CREATE TABLE agency_mandates (
 id UUID PRIMARY KEY,owner_id UUID NOT NULL REFERENCES organizations(id),agency_id UUID NOT NULL REFERENCES organizations(id),
 property_id UUID NOT NULL REFERENCES properties(id),property_version BIGINT NOT NULL,property_name VARCHAR(150) NOT NULL,
 terms JSONB NOT NULL,starts_at TIMESTAMPTZ NOT NULL,ends_at TIMESTAMPTZ NOT NULL CHECK(ends_at>starts_at),
 status VARCHAR(15) NOT NULL CHECK(status IN('SENT','ACTIVE','DECLINED','CANCELLED','REVOKED')),
 version BIGINT NOT NULL DEFAULT 0,requested_by UUID NOT NULL REFERENCES app_users(id),accepted_by UUID REFERENCES app_users(id),
 accepted_at TIMESTAMPTZ,closed_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 CHECK(owner_id<>agency_id),CHECK(status<>'ACTIVE' OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL))
);
CREATE INDEX agency_mandate_owner ON agency_mandates(owner_id,created_at DESC,id);
CREATE INDEX agency_mandate_agency ON agency_mandates(agency_id,created_at DESC,id);
ALTER TABLE properties ADD COLUMN management_mandate_id UUID REFERENCES agency_mandates(id);
CREATE OR REPLACE FUNCTION agency_management_access(agency UUID, property UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM properties p WHERE p.id=property AND
 (agency=p.owner_organization_id OR (agency=p.manager_organization_id AND
 (p.management_mandate_id IS NULL OR EXISTS(SELECT 1 FROM agency_mandates m WHERE m.id=p.management_mandate_id
 AND m.property_id=p.id AND m.agency_id=agency AND m.owner_id=p.owner_organization_id AND m.status='ACTIVE' AND m.starts_at<=now() AND m.ends_at>now())))))
$$;
CREATE OR REPLACE FUNCTION revoke_agency_property_assignments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE agency_property_assignments SET active=false,version=version+1,updated_at=clock_timestamp()
 WHERE property_id=OLD.id AND active AND (NEW.status<>'ACTIVE'
 OR agency_id NOT IN(NEW.owner_organization_id,COALESCE(NEW.manager_organization_id,NEW.owner_organization_id))
 OR NEW.management_mandate_id IS DISTINCT FROM OLD.management_mandate_id);
 RETURN NEW;
END $$;
CREATE FUNCTION protect_agency_mandate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.id,OLD.owner_id,OLD.agency_id,OLD.property_id,OLD.property_version,OLD.property_name,OLD.terms,OLD.starts_at,OLD.ends_at,OLD.requested_by,OLD.created_at)
 IS DISTINCT FROM (NEW.id,NEW.owner_id,NEW.agency_id,NEW.property_id,NEW.property_version,NEW.property_name,NEW.terms,NEW.starts_at,NEW.ends_at,NEW.requested_by,NEW.created_at)
 THEN RAISE EXCEPTION 'Offered mandate terms and parties are immutable';END IF;
 IF OLD.accepted_by IS NOT NULL AND (OLD.accepted_by,OLD.accepted_at) IS DISTINCT FROM (NEW.accepted_by,NEW.accepted_at)
 THEN RAISE EXCEPTION 'Mandate acceptance evidence is immutable';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER agency_mandate_terms BEFORE UPDATE ON agency_mandates FOR EACH ROW EXECUTE FUNCTION protect_agency_mandate();
CREATE TRIGGER agency_mandate_retained BEFORE DELETE OR TRUNCATE ON agency_mandates FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
