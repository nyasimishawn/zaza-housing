CREATE TABLE property_operational_notices (
 id UUID PRIMARY KEY,property_id UUID NOT NULL REFERENCES properties(id),lease_id UUID NOT NULL REFERENCES rental_leases(id),
 tenant_id UUID NOT NULL REFERENCES app_users(id),owner_organization_id UUID NOT NULL REFERENCES organizations(id),
 property_name VARCHAR(150) NOT NULL,unit_label VARCHAR(100) NOT NULL,tenant_name VARCHAR(100) NOT NULL,
 kind VARCHAR(15) NOT NULL CHECK(kind IN('INSPECTION','MOVE_IN','MOVE_OUT','GENERAL')),title VARCHAR(150) NOT NULL,message VARCHAR(4000) NOT NULL,
 starts_at TIMESTAMPTZ,ends_at TIMESTAMPTZ,status VARCHAR(12) NOT NULL CHECK(status IN('DRAFT','PUBLISHED','CANCELLED')),
 tenant_response VARCHAR(15) CHECK(tenant_response IN('ACKNOWLEDGE','ACCEPT','DECLINE','RESCHEDULE')),
 version BIGINT NOT NULL CHECK(version>=0),created_by UUID NOT NULL REFERENCES app_users(id),created_by_organization_id UUID NOT NULL REFERENCES organizations(id),
 published_at TIMESTAMPTZ,published_by UUID REFERENCES app_users(id),cancelled_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 CHECK((starts_at IS NULL AND ends_at IS NULL) OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at>=starts_at+interval '15 minutes' AND ends_at<=starts_at+interval '120 minutes')),
 CHECK(kind<>'INSPECTION' OR starts_at IS NOT NULL),CHECK(status<>'PUBLISHED' OR (published_at IS NOT NULL AND published_by IS NOT NULL)),
 CHECK(status<>'CANCELLED' OR cancelled_at IS NOT NULL)
);
CREATE INDEX operational_notices_property ON property_operational_notices(property_id,created_at DESC,id);
CREATE INDEX operational_notices_tenant ON property_operational_notices(tenant_id,created_at DESC,id) WHERE published_at IS NOT NULL;
CREATE FUNCTION protect_operational_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (OLD.id,OLD.property_id,OLD.lease_id,OLD.tenant_id,OLD.owner_organization_id,OLD.property_name,OLD.unit_label,OLD.tenant_name,OLD.kind,OLD.title,OLD.message,OLD.starts_at,OLD.ends_at,OLD.created_by,OLD.created_by_organization_id,OLD.created_at)
 IS DISTINCT FROM (NEW.id,NEW.property_id,NEW.lease_id,NEW.tenant_id,NEW.owner_organization_id,NEW.property_name,NEW.unit_label,NEW.tenant_name,NEW.kind,NEW.title,NEW.message,NEW.starts_at,NEW.ends_at,NEW.created_by,NEW.created_by_organization_id,NEW.created_at)
 THEN RAISE EXCEPTION 'Operational notice content is immutable; cancel and prepare a new notice'; END IF;
 IF OLD.published_at IS NOT NULL AND (OLD.published_at,OLD.published_by) IS DISTINCT FROM (NEW.published_at,NEW.published_by) THEN RAISE EXCEPTION 'Publication evidence is immutable';END IF;
 IF OLD.status='CANCELLED' THEN RAISE EXCEPTION 'Cancelled notice history is immutable';END IF;
 RETURN NEW;END $$;
CREATE TRIGGER operational_notice_identity BEFORE UPDATE ON property_operational_notices FOR EACH ROW EXECUTE FUNCTION protect_operational_notice();
CREATE TRIGGER operational_notice_retain BEFORE DELETE OR TRUNCATE ON property_operational_notices FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
