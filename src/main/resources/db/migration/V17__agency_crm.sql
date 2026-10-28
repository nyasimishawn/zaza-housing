CREATE FUNCTION agency_management_access(agency UUID, property UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM properties p WHERE p.id=property AND agency IN(p.owner_organization_id,p.manager_organization_id))
$$;
CREATE FUNCTION agency_property_access(agency UUID, property UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM properties p WHERE p.id=property AND p.status='ACTIVE' AND agency_management_access(agency,property))
$$;
CREATE TABLE agency_clients (
 id UUID PRIMARY KEY, agency_id UUID NOT NULL REFERENCES organizations(id), customer_user_id UUID REFERENCES app_users(id),
 details JSONB NOT NULL CHECK(jsonb_typeof(details)='object'), status VARCHAR(12) NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_by UUID NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL, UNIQUE(agency_id,id)
);
CREATE INDEX agency_clients_scope ON agency_clients(agency_id,updated_at DESC,id);
CREATE TABLE agency_property_assignments (
 id UUID PRIMARY KEY,agency_id UUID NOT NULL REFERENCES organizations(id),property_id UUID NOT NULL REFERENCES properties(id),
 agent_id UUID NOT NULL REFERENCES app_users(id),active BOOLEAN NOT NULL,version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,UNIQUE(agency_id,property_id,agent_id)
);
CREATE TABLE agency_leads (
 id UUID PRIMARY KEY,agency_id UUID NOT NULL REFERENCES organizations(id),client_id UUID NOT NULL,
 title VARCHAR(150) NOT NULL,stage VARCHAR(20) NOT NULL CHECK(stage IN ('NEW','QUALIFIED','VIEWING','APPLICATION','WON','LOST')),
 assigned_to UUID REFERENCES app_users(id),property_id UUID REFERENCES properties(id),listing_id UUID REFERENCES rental_listings(id),
 enquiry_id UUID REFERENCES conversations(id),viewing_id UUID REFERENCES viewing_appointments(id),
 application_id UUID REFERENCES rental_applications(id),lease_id UUID REFERENCES rental_leases(id),won_at TIMESTAMPTZ,
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_by UUID NOT NULL REFERENCES app_users(id),created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(agency_id,client_id) REFERENCES agency_clients(agency_id,id),UNIQUE(agency_id,id),
 CHECK((listing_id IS NULL AND enquiry_id IS NULL AND viewing_id IS NULL AND application_id IS NULL AND lease_id IS NULL) OR property_id IS NOT NULL),
 CHECK(stage<>'WON' OR (lease_id IS NOT NULL AND application_id IS NOT NULL AND won_at IS NOT NULL))
);
CREATE UNIQUE INDEX agency_lead_one_enquiry ON agency_leads(agency_id,enquiry_id) WHERE enquiry_id IS NOT NULL;
CREATE INDEX agency_leads_scope ON agency_leads(agency_id,assigned_to,stage,updated_at DESC,id);
CREATE TABLE agency_tasks (
 id UUID PRIMARY KEY,agency_id UUID NOT NULL REFERENCES organizations(id),lead_id UUID NOT NULL,assignee_id UUID NOT NULL REFERENCES app_users(id),
 title VARCHAR(150) NOT NULL,kind VARCHAR(15) NOT NULL CHECK(kind IN ('CALL','EMAIL','FOLLOW_UP','VIEWING','OTHER')),
 due_at TIMESTAMPTZ NOT NULL,description VARCHAR(2000) NOT NULL,status VARCHAR(15) NOT NULL CHECK(status IN ('OPEN','DONE','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(agency_id,lead_id) REFERENCES agency_leads(agency_id,id)
);
CREATE INDEX agency_tasks_due ON agency_tasks(agency_id,assignee_id,status,due_at,id);
CREATE TABLE agency_operations (
 agency_id UUID NOT NULL REFERENCES organizations(id),request_id UUID NOT NULL,actor_id UUID NOT NULL REFERENCES app_users(id),
 action VARCHAR(40) NOT NULL,target_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(agency_id,request_id)
);
CREATE TABLE agency_events (
 id UUID PRIMARY KEY,agency_id UUID NOT NULL REFERENCES organizations(id),target_kind VARCHAR(20) NOT NULL,target_id UUID NOT NULL,
 actor_id UUID NOT NULL REFERENCES app_users(id),action VARCHAR(40) NOT NULL,message VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX agency_events_target ON agency_events(agency_id,target_kind,target_id,created_at DESC,id);
CREATE FUNCTION revoke_agency_member_assignments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE agency_property_assignments SET active=false,version=version+1,updated_at=clock_timestamp()
 WHERE agency_id=OLD.organization_id AND agent_id=OLD.user_id AND active;
 UPDATE agency_leads SET assigned_to=NULL,version=version+1,updated_at=clock_timestamp()
 WHERE agency_id=OLD.organization_id AND assigned_to=OLD.user_id;
 RETURN OLD;
END $$;
CREATE TRIGGER agency_membership_removal BEFORE DELETE ON organization_memberships FOR EACH ROW EXECUTE FUNCTION revoke_agency_member_assignments();
CREATE FUNCTION revoke_agency_property_assignments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE agency_property_assignments SET active=false,version=version+1,updated_at=clock_timestamp()
 WHERE property_id=OLD.id AND active AND (NEW.status<>'ACTIVE' OR agency_id NOT IN(NEW.owner_organization_id,COALESCE(NEW.manager_organization_id,NEW.owner_organization_id)));
 RETURN NEW;
END $$;
CREATE TRIGGER agency_property_revocation BEFORE UPDATE ON properties FOR EACH ROW EXECUTE FUNCTION revoke_agency_property_assignments();
CREATE FUNCTION protect_agency_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.id,OLD.agency_id,OLD.created_at) IS DISTINCT FROM (NEW.id,NEW.agency_id,NEW.created_at) THEN
   RAISE EXCEPTION 'Agency record scope is immutable';
 END IF;
 IF TG_TABLE_NAME='agency_leads' THEN
  IF OLD.client_id IS DISTINCT FROM NEW.client_id OR OLD.created_by IS DISTINCT FROM NEW.created_by
   OR (OLD.stage='WON' AND (NEW.stage<>'WON' OR (OLD.lease_id,OLD.application_id,OLD.won_at) IS DISTINCT FROM (NEW.lease_id,NEW.application_id,NEW.won_at))) THEN
    RAISE EXCEPTION 'Converted lead evidence is immutable';
  END IF;
 END IF;
 IF TG_TABLE_NAME='agency_clients' THEN
  IF OLD.customer_user_id IS NOT NULL AND OLD.customer_user_id IS DISTINCT FROM NEW.customer_user_id THEN
    RAISE EXCEPTION 'Registered customer binding is immutable';
  END IF;
 END IF;
 IF TG_TABLE_NAME='agency_property_assignments' THEN
  IF (OLD.property_id,OLD.agent_id) IS DISTINCT FROM (NEW.property_id,NEW.agent_id) THEN RAISE EXCEPTION 'Property assignment identity is immutable'; END IF;
 END IF;
 IF TG_TABLE_NAME='agency_tasks' THEN
  IF (OLD.lead_id,OLD.assignee_id) IS DISTINCT FROM (NEW.lead_id,NEW.assignee_id) THEN RAISE EXCEPTION 'Task assignment identity is immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agency_clients','agency_leads','agency_tasks','agency_property_assignments'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION protect_agency_record()',t||'_identity',t);
  EXECUTE format('CREATE TRIGGER %I BEFORE DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_retain',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['agency_events','agency_operations'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
 END LOOP;
END $$;
