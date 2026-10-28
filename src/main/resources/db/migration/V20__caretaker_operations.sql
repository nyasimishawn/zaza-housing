CREATE TABLE caretaker_assignments (
 id UUID PRIMARY KEY,organization_id UUID NOT NULL REFERENCES organizations(id),property_id UUID NOT NULL REFERENCES properties(id),
 user_id UUID NOT NULL REFERENCES app_users(id),user_name VARCHAR(100) NOT NULL,property_snapshot JSONB NOT NULL,
 property_version BIGINT NOT NULL,capabilities JSONB NOT NULL,responsibilities VARCHAR(2000) NOT NULL,
 status VARCHAR(12) NOT NULL CHECK(status IN('INVITED','ACTIVE','DECLINED','REVOKED','EXPIRED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),expires_at TIMESTAMPTZ NOT NULL,invited_by UUID NOT NULL REFERENCES app_users(id),
 accepted_at TIMESTAMPTZ,closed_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 CHECK(jsonb_typeof(capabilities)='array' AND capabilities @> '["OPERATIONS"]'::jsonb
 AND capabilities <@ '["OPERATIONS","INSPECTIONS","MAINTENANCE","NOTICES"]'::jsonb),
 CHECK(status<>'ACTIVE' OR accepted_at IS NOT NULL),CHECK(expires_at>created_at)
);
CREATE UNIQUE INDEX caretaker_one_open_grant ON caretaker_assignments(organization_id,property_id,user_id) WHERE status IN('INVITED','ACTIVE');
CREATE INDEX caretaker_grants_user ON caretaker_assignments(user_id,organization_id,status,expires_at);
CREATE FUNCTION caretaker_property_access(scope UUID, property UUID, actor UUID, capability TEXT) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM properties p JOIN organization_memberships m ON m.organization_id=scope AND m.user_id=actor
 JOIN organizations o ON o.id=m.organization_id AND o.type IN('LANDLORD','AGENCY') JOIN app_users u ON u.id=m.user_id AND u.active
 WHERE p.id=property AND agency_management_access(scope,p.id) AND (m.role IN('OWNER','ADMIN') OR
 (p.status='ACTIVE' AND EXISTS(SELECT 1 FROM caretaker_assignments g WHERE g.organization_id=scope AND g.property_id=p.id
 AND g.user_id=actor AND g.status='ACTIVE' AND g.expires_at>statement_timestamp() AND jsonb_exists(g.capabilities,capability)))))
$$;
CREATE TABLE property_operations_tasks (
 id UUID PRIMARY KEY,owner_organization_id UUID NOT NULL REFERENCES organizations(id),property_id UUID NOT NULL REFERENCES properties(id),
 unit_id UUID,property_name VARCHAR(150) NOT NULL,location_label VARCHAR(150) NOT NULL,
 assignee_organization_id UUID NOT NULL REFERENCES organizations(id),assignee_id UUID NOT NULL REFERENCES app_users(id),assignee_name VARCHAR(100) NOT NULL,
 kind VARCHAR(15) NOT NULL CHECK(kind IN('GENERAL','CLEANING','SAFETY','MAINTENANCE','MOVE_IN','MOVE_OUT','INSPECTION')),
 priority VARCHAR(10) NOT NULL CHECK(priority IN('LOW','NORMAL','HIGH','URGENT')),title VARCHAR(150) NOT NULL,description VARCHAR(4000) NOT NULL,
 due_at TIMESTAMPTZ NOT NULL,status VARCHAR(15) NOT NULL CHECK(status IN('OPEN','IN_PROGRESS','BLOCKED','SUBMITTED','COMPLETED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),completion_summary VARCHAR(2000),submitted_at TIMESTAMPTZ,completed_at TIMESTAMPTZ,
 completed_by UUID REFERENCES app_users(id),created_by UUID NOT NULL REFERENCES app_users(id),created_by_organization_id UUID NOT NULL REFERENCES organizations(id),
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(property_id,unit_id) REFERENCES property_units(property_id,id),
 CHECK(status NOT IN('SUBMITTED','COMPLETED') OR (completion_summary IS NOT NULL AND submitted_at IS NOT NULL)),
 CHECK(status<>'COMPLETED' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX property_tasks_scope ON property_operations_tasks(property_id,assignee_id,status,due_at,id);
CREATE TABLE caretaker_operations (
 organization_id UUID NOT NULL REFERENCES organizations(id),request_id UUID NOT NULL,actor_id UUID NOT NULL REFERENCES app_users(id),
 action VARCHAR(50) NOT NULL,target_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL,PRIMARY KEY(organization_id,request_id)
);
CREATE TABLE caretaker_events (
 id UUID PRIMARY KEY,property_id UUID NOT NULL REFERENCES properties(id),target_kind VARCHAR(20) NOT NULL,target_id UUID NOT NULL,
 actor_id UUID NOT NULL REFERENCES app_users(id),organization_id UUID NOT NULL REFERENCES organizations(id),action VARCHAR(50) NOT NULL,
 message VARCHAR(4000) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX caretaker_events_target ON caretaker_events(target_kind,target_id,created_at DESC,id);
CREATE TABLE property_task_evidence (
 id UUID PRIMARY KEY,task_id UUID NOT NULL REFERENCES property_operations_tasks(id),author_id UUID NOT NULL REFERENCES app_users(id),
 caption VARCHAR(200) NOT NULL,storage_key VARCHAR(50) NOT NULL UNIQUE,content_type VARCHAR(50) NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,
 byte_size BIGINT NOT NULL CHECK(byte_size>0),sha256 CHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE FUNCTION caretaker_membership_revocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE caretaker_assignments SET status='REVOKED',closed_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
 WHERE organization_id=OLD.organization_id AND user_id=OLD.user_id AND status IN('INVITED','ACTIVE');
 RETURN OLD;
END $$;
CREATE TRIGGER caretaker_membership_revocation BEFORE DELETE ON organization_memberships FOR EACH ROW EXECUTE FUNCTION caretaker_membership_revocation();
CREATE FUNCTION caretaker_property_revocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE caretaker_assignments SET status='REVOKED',closed_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
 WHERE property_id=OLD.id AND status IN('INVITED','ACTIVE') AND (NEW.status<>'ACTIVE'
 OR NEW.owner_organization_id IS DISTINCT FROM OLD.owner_organization_id
 OR organization_id NOT IN(NEW.owner_organization_id,COALESCE(NEW.manager_organization_id,NEW.owner_organization_id))
 OR (organization_id<>NEW.owner_organization_id AND NEW.management_mandate_id IS DISTINCT FROM OLD.management_mandate_id));
 RETURN NEW;
END $$;
CREATE TRIGGER caretaker_property_revocation BEFORE UPDATE ON properties FOR EACH ROW EXECUTE FUNCTION caretaker_property_revocation();
CREATE FUNCTION protect_caretaker_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='caretaker_assignments' THEN
  IF (OLD.id,OLD.organization_id,OLD.property_id,OLD.user_id,OLD.user_name,OLD.property_snapshot,OLD.property_version,OLD.capabilities,OLD.responsibilities,OLD.expires_at,OLD.invited_by,OLD.created_at)
  IS DISTINCT FROM (NEW.id,NEW.organization_id,NEW.property_id,NEW.user_id,NEW.user_name,NEW.property_snapshot,NEW.property_version,NEW.capabilities,NEW.responsibilities,NEW.expires_at,NEW.invited_by,NEW.created_at)
  THEN RAISE EXCEPTION 'Caretaker assignment terms are immutable';END IF;
  IF OLD.accepted_at IS NOT NULL AND OLD.accepted_at IS DISTINCT FROM NEW.accepted_at THEN RAISE EXCEPTION 'Acceptance evidence is immutable';END IF;
  IF OLD.status NOT IN('INVITED','ACTIVE') THEN RAISE EXCEPTION 'Closed caretaker assignments are immutable';END IF;
 ELSE
  IF (OLD.id,OLD.owner_organization_id,OLD.property_id,OLD.unit_id,OLD.property_name,OLD.location_label,OLD.kind,OLD.priority,OLD.title,OLD.description,OLD.created_by,OLD.created_by_organization_id,OLD.created_at)
  IS DISTINCT FROM (NEW.id,NEW.owner_organization_id,NEW.property_id,NEW.unit_id,NEW.property_name,NEW.location_label,NEW.kind,NEW.priority,NEW.title,NEW.description,NEW.created_by,NEW.created_by_organization_id,NEW.created_at)
  THEN RAISE EXCEPTION 'Task scope and original instructions are immutable';END IF;
  IF OLD.status IN('COMPLETED','CANCELLED') THEN RAISE EXCEPTION 'Closed tasks are immutable';END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['caretaker_assignments','property_operations_tasks'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION protect_caretaker_record()',t||'_identity',t);
  EXECUTE format('CREATE TRIGGER %I BEFORE DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_retain',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['caretaker_operations','caretaker_events','property_task_evidence'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
 END LOOP;
END $$;
ALTER TABLE in_app_notifications ADD COLUMN caretaker_target_id UUID;
ALTER TABLE in_app_notifications ADD COLUMN caretaker_event_id UUID REFERENCES caretaker_events(id);
ALTER TABLE in_app_notifications ADD COLUMN caretaker_url TEXT;
CREATE UNIQUE INDEX caretaker_notification_event ON in_app_notifications(user_id,caretaker_event_id) WHERE caretaker_event_id IS NOT NULL;

DO $$ DECLARE previous TEXT; BEGIN
 SELECT pg_get_expr(conbin,conrelid) INTO previous FROM pg_constraint WHERE conrelid='in_app_notifications'::regclass AND conname='notification_event_source';
 ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
 EXECUTE format('ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK ((caretaker_event_id IS NULL AND caretaker_target_id IS NULL AND caretaker_url IS NULL AND (%s)) OR (caretaker_event_id IS NOT NULL AND caretaker_target_id IS NOT NULL AND caretaker_url IS NOT NULL AND num_nonnulls(event_id,lease_event_id,billing_event_id,work_event_id,service_finance_event_id,conversation_event_id,vault_event_id,application_id,lease_id,work_request_id,service_payment_account_id,conversation_id,lease_url,work_request_url,service_payment_url,conversation_url,vault_url)=0))',previous);
END $$;
