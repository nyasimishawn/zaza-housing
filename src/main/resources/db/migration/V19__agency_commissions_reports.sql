ALTER TABLE agency_leads ADD COLUMN won_agent_id UUID REFERENCES app_users(id);
UPDATE agency_leads SET won_agent_id=assigned_to WHERE stage='WON';
CREATE FUNCTION protect_agency_conversion_agent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.stage='WON' AND OLD.won_agent_id IS DISTINCT FROM NEW.won_agent_id THEN RAISE EXCEPTION 'Conversion attribution is immutable';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER agency_conversion_agent BEFORE UPDATE ON agency_leads FOR EACH ROW EXECUTE FUNCTION protect_agency_conversion_agent();
CREATE TABLE agency_commissions (
 id UUID PRIMARY KEY,agency_id UUID NOT NULL REFERENCES organizations(id),lead_id UUID NOT NULL,lease_id UUID NOT NULL REFERENCES rental_leases(id),
 agent_id UUID NOT NULL REFERENCES app_users(id),lead_title VARCHAR(150) NOT NULL,agent_name VARCHAR(100) NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0),currency CHAR(3) NOT NULL,agreement VARCHAR(2000) NOT NULL,
 status VARCHAR(15) NOT NULL CHECK(status IN('SUBMITTED','APPROVED','REJECTED','PAID','VOID')),
 version BIGINT NOT NULL DEFAULT 0,created_by UUID NOT NULL REFERENCES app_users(id),approved_by UUID REFERENCES app_users(id),approved_at TIMESTAMPTZ,
 paid_by UUID REFERENCES app_users(id),paid_at TIMESTAMPTZ,external_reference VARCHAR(150),created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(agency_id,lead_id,agent_id),FOREIGN KEY(agency_id,lead_id) REFERENCES agency_leads(agency_id,id),
 CHECK(approved_by IS NULL OR approved_by<>agent_id),CHECK(paid_by IS NULL OR paid_by<>agent_id),
 CHECK(status NOT IN('APPROVED','PAID') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
 CHECK(status<>'PAID' OR (paid_by IS NOT NULL AND paid_at IS NOT NULL AND external_reference IS NOT NULL))
);
CREATE UNIQUE INDEX agency_commission_payment_reference ON agency_commissions(agency_id,external_reference) WHERE external_reference IS NOT NULL;
CREATE INDEX agency_commission_scope ON agency_commissions(agency_id,agent_id,status,created_at DESC,id);
CREATE FUNCTION protect_agency_commission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.id,OLD.agency_id,OLD.lead_id,OLD.lease_id,OLD.agent_id,OLD.lead_title,OLD.agent_name,OLD.amount,OLD.currency,OLD.agreement,OLD.created_by,OLD.created_at)
 IS DISTINCT FROM (NEW.id,NEW.agency_id,NEW.lead_id,NEW.lease_id,NEW.agent_id,NEW.lead_title,NEW.agent_name,NEW.amount,NEW.currency,NEW.agreement,NEW.created_by,NEW.created_at)
 THEN RAISE EXCEPTION 'Commission agreement and source are immutable';END IF;
 IF OLD.approved_by IS NOT NULL AND (OLD.approved_by,OLD.approved_at) IS DISTINCT FROM (NEW.approved_by,NEW.approved_at) THEN RAISE EXCEPTION 'Approval evidence is immutable';END IF;
 IF OLD.status='PAID' THEN RAISE EXCEPTION 'Paid commission records are immutable';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER agency_commission_identity BEFORE UPDATE ON agency_commissions FOR EACH ROW EXECUTE FUNCTION protect_agency_commission();
CREATE TRIGGER agency_commission_retained BEFORE DELETE OR TRUNCATE ON agency_commissions FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
