CREATE TABLE conversations (
 id UUID PRIMARY KEY,kind VARCHAR(10) NOT NULL CHECK(kind IN ('ENQUIRY','LEASE','WORK')),
 channel VARCHAR(12) NOT NULL CHECK(channel IN ('SHARED','PROCUREMENT')),
 listing_id UUID REFERENCES rental_listings(id),lease_id UUID REFERENCES rental_leases(id),work_id UUID REFERENCES work_requests(id),
 property_id UUID NOT NULL REFERENCES properties(id),unit_id UUID NOT NULL,
 customer_id UUID NOT NULL REFERENCES app_users(id),provider_id UUID REFERENCES organizations(id),assignment_id UUID REFERENCES work_assignments(id),
 subject VARCHAR(180) NOT NULL,status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),last_sequence BIGINT NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(property_id,unit_id) REFERENCES property_units(property_id,id),
 CHECK((kind='ENQUIRY' AND listing_id IS NOT NULL AND lease_id IS NULL AND work_id IS NULL AND channel='SHARED' AND provider_id IS NULL AND assignment_id IS NULL)
 OR(kind='LEASE' AND lease_id IS NOT NULL AND listing_id IS NULL AND work_id IS NULL AND channel='SHARED' AND provider_id IS NULL AND assignment_id IS NULL)
 OR(kind='WORK' AND work_id IS NOT NULL AND listing_id IS NULL AND lease_id IS NULL)),
 CHECK((provider_id IS NULL)=(assignment_id IS NULL))
);
CREATE UNIQUE INDEX enquiry_conversation_unique ON conversations(listing_id,customer_id) WHERE kind='ENQUIRY';
CREATE UNIQUE INDEX lease_conversation_unique ON conversations(lease_id) WHERE kind='LEASE';
CREATE UNIQUE INDEX work_conversation_unique ON conversations(work_id,assignment_id,channel) NULLS NOT DISTINCT WHERE kind='WORK';
CREATE INDEX conversation_property ON conversations(property_id,updated_at DESC,id);
CREATE INDEX conversation_customer ON conversations(customer_id,updated_at DESC,id);
CREATE INDEX conversation_work ON conversations(work_id,assignment_id);
CREATE TABLE conversation_messages (
 id UUID PRIMARY KEY,conversation_id UUID NOT NULL REFERENCES conversations(id),sequence BIGINT NOT NULL CHECK(sequence>0),
 author_id UUID NOT NULL REFERENCES app_users(id),sender_role VARCHAR(10) NOT NULL CHECK(sender_role IN ('CUSTOMER','MANAGER','PROVIDER')),
 body VARCHAR(4000) NOT NULL CHECK(length(trim(body))>0),created_at TIMESTAMPTZ NOT NULL,UNIQUE(conversation_id,sequence),UNIQUE(conversation_id,id)
);
CREATE INDEX conversation_author_rate ON conversation_messages(conversation_id,author_id,created_at);
CREATE TABLE conversation_message_redactions (
 message_id UUID PRIMARY KEY REFERENCES conversation_messages(id),actor_id UUID NOT NULL REFERENCES app_users(id),
 reason VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE conversation_preferences (
 conversation_id UUID NOT NULL REFERENCES conversations(id),user_id UUID NOT NULL REFERENCES app_users(id),
 read_through BIGINT NOT NULL DEFAULT 0 CHECK(read_through>=0),muted BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(conversation_id,user_id)
);
CREATE TABLE conversation_operations (
 scope_id UUID NOT NULL,request_id UUID NOT NULL,actor_id UUID NOT NULL REFERENCES app_users(id),
 action VARCHAR(50) NOT NULL,fingerprint CHAR(64) NOT NULL,entity_id UUID NOT NULL,PRIMARY KEY(scope_id,request_id)
);
CREATE TABLE conversation_events (
 id UUID PRIMARY KEY,conversation_id UUID NOT NULL REFERENCES conversations(id),actor_id UUID NOT NULL REFERENCES app_users(id),
 type VARCHAR(50) NOT NULL,entity_id UUID NOT NULL,message VARCHAR(2000) NOT NULL,shared BOOLEAN NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX conversation_event_history ON conversation_events(conversation_id,created_at,id);
CREATE TABLE conversation_reports (
 id UUID PRIMARY KEY,conversation_id UUID NOT NULL, message_id UUID NOT NULL,reporter_id UUID NOT NULL REFERENCES app_users(id),
 category VARCHAR(12) NOT NULL CHECK(category IN ('SPAM','HARASSMENT','FRAUD','OTHER')),reason VARCHAR(2000) NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ASSIGNED','RESOLVED')),
 assigned_to UUID REFERENCES app_users(id),resolution VARCHAR(12) CHECK(resolution IN ('REDACT','NO_ACTION')),
 resolution_reason VARCHAR(2000),version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(conversation_id,message_id) REFERENCES conversation_messages(conversation_id,id),UNIQUE(message_id,reporter_id),
 CHECK((status='OPEN' AND assigned_to IS NULL AND resolution IS NULL AND resolution_reason IS NULL)
 OR(status='ASSIGNED' AND assigned_to IS NOT NULL AND resolution IS NULL AND resolution_reason IS NULL)
 OR(status='RESOLVED' AND assigned_to IS NOT NULL AND resolution IS NOT NULL AND resolution_reason IS NOT NULL))
);
CREATE TABLE conversation_report_decisions (
 id UUID PRIMARY KEY,report_id UUID NOT NULL REFERENCES conversation_reports(id),actor_id UUID NOT NULL REFERENCES app_users(id),
 action VARCHAR(12) NOT NULL CHECK(action IN ('ASSIGNED','RELEASED','REDACT','NO_ACTION')),reason VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX conversation_report_queue ON conversation_reports(status,created_at,id);
CREATE TABLE viewing_appointments (
 id UUID PRIMARY KEY,conversation_id UUID NOT NULL REFERENCES conversations(id),
 current_proposal_id UUID NOT NULL,confirmed_proposal_id UUID,
 status VARCHAR(22) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CONFIRMED','RESCHEDULE_PENDING','DECLINED','CANCELLED','COMPLETED','NO_SHOW')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_at TIMESTAMPTZ NOT NULL,
 CHECK(status NOT IN ('CONFIRMED','RESCHEDULE_PENDING','COMPLETED','NO_SHOW') OR confirmed_proposal_id IS NOT NULL),UNIQUE(conversation_id,id)
);
CREATE TABLE viewing_proposals (
 id UUID PRIMARY KEY,viewing_id UUID NOT NULL REFERENCES viewing_appointments(id) DEFERRABLE INITIALLY DEFERRED,
 proposed_by UUID NOT NULL REFERENCES app_users(id),proposed_role VARCHAR(10) NOT NULL CHECK(proposed_role IN ('CUSTOMER','MANAGER')),
 start_at TIMESTAMPTZ NOT NULL,end_at TIMESTAMPTZ NOT NULL CHECK(end_at>start_at),listing_version BIGINT NOT NULL CHECK(listing_version>=0),
 meeting_instructions VARCHAR(1000) NOT NULL,reason VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL,UNIQUE(viewing_id,id)
);
ALTER TABLE viewing_appointments ADD FOREIGN KEY(id,current_proposal_id) REFERENCES viewing_proposals(viewing_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE viewing_appointments ADD FOREIGN KEY(id,confirmed_proposal_id) REFERENCES viewing_proposals(viewing_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX viewing_one_open_per_enquiry ON viewing_appointments(conversation_id) WHERE status IN ('PENDING','CONFIRMED','RESCHEDULE_PENDING');
CREATE INDEX viewing_conversation ON viewing_appointments(conversation_id,created_at,id);
CREATE INDEX viewing_proposal_time ON viewing_proposals(start_at,end_at);
CREATE TABLE viewing_history (
 id UUID PRIMARY KEY,viewing_id UUID NOT NULL REFERENCES viewing_appointments(id),proposal_id UUID NOT NULL REFERENCES viewing_proposals(id),
 actor_id UUID NOT NULL REFERENCES app_users(id),role VARCHAR(10) NOT NULL CHECK(role IN ('CUSTOMER','MANAGER')),
 action VARCHAR(25) NOT NULL,reason VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX viewing_history_order ON viewing_history(viewing_id,created_at,id);

DO $$ DECLARE t TEXT;BEGIN
 FOREACH t IN ARRAY ARRAY['conversation_messages','conversation_message_redactions','conversation_operations','conversation_events','conversation_report_decisions','viewing_proposals','viewing_history'] LOOP
 EXECUTE format('CREATE TRIGGER immutable_communications BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t);
 EXECUTE format('CREATE TRIGGER no_truncate_communications BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['conversations','conversation_reports','viewing_appointments'] LOOP
 EXECUTE format('CREATE TRIGGER no_delete_communications BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t);
 EXECUTE format('CREATE TRIGGER no_truncate_communications BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t);
 END LOOP;
END $$;
CREATE FUNCTION protect_conversation_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.id,NEW.kind,NEW.channel,NEW.listing_id,NEW.lease_id,NEW.work_id,NEW.property_id,NEW.unit_id,NEW.customer_id,NEW.provider_id,NEW.assignment_id,NEW.subject,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.kind,OLD.channel,OLD.listing_id,OLD.lease_id,OLD.work_id,OLD.property_id,OLD.unit_id,OLD.customer_id,OLD.provider_id,OLD.assignment_id,OLD.subject,OLD.created_at)
 THEN RAISE EXCEPTION 'Conversation identity is immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER conversation_identity BEFORE UPDATE ON conversations FOR EACH ROW EXECUTE FUNCTION protect_conversation_identity();
CREATE FUNCTION protect_viewing_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.id,NEW.conversation_id,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.conversation_id,OLD.created_at)
 THEN RAISE EXCEPTION 'Viewing identity is immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER viewing_identity BEFORE UPDATE ON viewing_appointments FOR EACH ROW EXECUTE FUNCTION protect_viewing_identity();
CREATE FUNCTION protect_conversation_report_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.id,NEW.conversation_id,NEW.message_id,NEW.reporter_id,NEW.category,NEW.reason,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.conversation_id,OLD.message_id,OLD.reporter_id,OLD.category,OLD.reason,OLD.created_at)
 THEN RAISE EXCEPTION 'Report source evidence is immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER conversation_report_identity BEFORE UPDATE ON conversation_reports FOR EACH ROW EXECUTE FUNCTION protect_conversation_report_identity();

ALTER TABLE in_app_notifications ADD COLUMN conversation_event_id UUID REFERENCES conversation_events(id);
ALTER TABLE in_app_notifications ADD COLUMN conversation_id UUID REFERENCES conversations(id);
ALTER TABLE in_app_notifications ADD COLUMN conversation_url VARCHAR(250);
ALTER TABLE in_app_notifications RENAME CONSTRAINT notification_event_source TO notification_event_source_v12;
-- Keep the full previous constraint as one branch, instead of weakening prior event-source rules.
ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source_v12;
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK (
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
);
CREATE UNIQUE INDEX notification_conversation_unique ON in_app_notifications(user_id,conversation_event_id);
