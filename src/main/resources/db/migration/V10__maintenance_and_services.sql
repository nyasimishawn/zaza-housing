CREATE TABLE service_provider_profiles (
 organization_id UUID PRIMARY KEY REFERENCES organizations(id), details JSONB NOT NULL CHECK(jsonb_typeof(details)='object'),
 status VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','PAUSED')),version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE TABLE service_offers (
 id UUID PRIMARY KEY,provider_id UUID NOT NULL REFERENCES service_provider_profiles(organization_id),details JSONB NOT NULL,
 category VARCHAR(40) NOT NULL,status VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','ARCHIVED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),UNIQUE(provider_id,id)
);
CREATE INDEX service_offers_directory ON service_offers(provider_id,category,status);
CREATE TABLE work_requests (
 id UUID PRIMARY KEY,kind VARCHAR(20) NOT NULL CHECK(kind IN ('MAINTENANCE','SERVICE_BOOKING')),
 lease_id UUID NOT NULL REFERENCES rental_leases(id),property_id UUID NOT NULL REFERENCES properties(id),unit_id UUID NOT NULL,
 tenant_id UUID NOT NULL REFERENCES app_users(id),reporter_id UUID NOT NULL REFERENCES app_users(id),owner_organization_id UUID NOT NULL REFERENCES organizations(id),
 category VARCHAR(40) NOT NULL,title VARCHAR(150) NOT NULL,description VARCHAR(8000) NOT NULL,
 priority VARCHAR(10) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 status VARCHAR(30) NOT NULL CHECK(status IN ('SUBMITTED','NEEDS_INFORMATION','TRIAGED','ASSIGNED','ACCEPTED','SCHEDULED','IN_PROGRESS','AWAITING_CONFIRMATION','AWAITING_APPROVAL','COMPLETED','DISPUTED','REJECTED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),provider_id UUID REFERENCES service_provider_profiles(organization_id),
 assignment_id UUID,quote_id UUID,visit_id UUID,dispute_id UUID,
 country_code CHAR(2) NOT NULL,city VARCHAR(100) NOT NULL,address TEXT NOT NULL,unit_label VARCHAR(150) NOT NULL,
 completion_summary VARCHAR(4000),completed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(property_id,unit_id) REFERENCES property_units(property_id,id),UNIQUE(property_id,id),
 CHECK((provider_id IS NULL AND assignment_id IS NULL) OR (provider_id IS NOT NULL AND assignment_id IS NOT NULL))
);
CREATE TABLE work_assignments (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),provider_id UUID NOT NULL,offer_id UUID NOT NULL,
 snapshot JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(provider_id,offer_id) REFERENCES service_offers(provider_id,id),UNIQUE(work_id,id)
);
ALTER TABLE work_requests ADD FOREIGN KEY(id,assignment_id) REFERENCES work_assignments(work_id,id);
CREATE TABLE service_quotes (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),assignment_id UUID NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK(amount>=0),currency CHAR(3) NOT NULL,scope VARCHAR(4000) NOT NULL,valid_until TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(work_id,assignment_id) REFERENCES work_assignments(work_id,id),UNIQUE(work_id,id)
);
CREATE TABLE service_quote_acceptances (
 quote_id UUID PRIMARY KEY REFERENCES service_quotes(id),actor_id UUID NOT NULL REFERENCES app_users(id),message VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE work_requests ADD FOREIGN KEY(id,quote_id) REFERENCES service_quotes(work_id,id);
CREATE TABLE work_visits (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),assignment_id UUID,
 start_at TIMESTAMPTZ NOT NULL,end_at TIMESTAMPTZ NOT NULL CHECK(end_at>start_at),access_instructions VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(work_id,assignment_id) REFERENCES work_assignments(work_id,id),UNIQUE(work_id,id)
);
CREATE INDEX work_visits_times ON work_visits(start_at,end_at);
CREATE TABLE work_visit_acceptances (
 visit_id UUID PRIMARY KEY REFERENCES work_visits(id),actor_id UUID NOT NULL REFERENCES app_users(id),message VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE work_requests ADD FOREIGN KEY(id,visit_id) REFERENCES work_visits(work_id,id);
CREATE TABLE work_disputes (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),party VARCHAR(15) NOT NULL,reason VARCHAR(2000) NOT NULL,previous_status VARCHAR(30) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),UNIQUE(work_id,id)
);
CREATE TABLE work_dispute_resolutions (
 dispute_id UUID PRIMARY KEY REFERENCES work_disputes(id),resolution VARCHAR(15) NOT NULL CHECK(resolution IN ('REWORK','WITHDRAWN')),
 actor_id UUID NOT NULL REFERENCES app_users(id),message VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE work_requests ADD FOREIGN KEY(id,dispute_id) REFERENCES work_disputes(work_id,id);
CREATE TABLE work_evidence (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),assignment_id UUID,provider_id UUID REFERENCES organizations(id),
 actor_id UUID NOT NULL REFERENCES app_users(id),party VARCHAR(15) NOT NULL,visibility VARCHAR(10) NOT NULL CHECK(visibility IN ('SHARED','INTERNAL')),
 caption VARCHAR(200) NOT NULL,storage_key VARCHAR(100) NOT NULL UNIQUE,content_type VARCHAR(20) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
 width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0),byte_size BIGINT NOT NULL CHECK(byte_size>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(work_id,assignment_id) REFERENCES work_assignments(work_id,id)
);
CREATE INDEX work_evidence_scope ON work_evidence(work_id,created_at,id);
CREATE TABLE service_reviews (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),assignment_id UUID NOT NULL UNIQUE,provider_id UUID NOT NULL REFERENCES organizations(id),
 reviewer_id UUID NOT NULL REFERENCES app_users(id),rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),comment VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(work_id,assignment_id) REFERENCES work_assignments(work_id,id)
);
CREATE INDEX service_reviews_provider ON service_reviews(provider_id,created_at,id);
CREATE TABLE work_events (
 id UUID PRIMARY KEY,work_id UUID NOT NULL REFERENCES work_requests(id),assignment_id UUID,provider_id UUID REFERENCES organizations(id),
 actor_id UUID NOT NULL REFERENCES app_users(id),party VARCHAR(15) NOT NULL,type VARCHAR(40) NOT NULL,status VARCHAR(30) NOT NULL,
 visibility VARCHAR(10) NOT NULL CHECK(visibility IN ('SHARED','INTERNAL')),message VARCHAR(4000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(work_id,assignment_id) REFERENCES work_assignments(work_id,id)
);
CREATE INDEX work_events_scope ON work_events(work_id,created_at,id);
CREATE TABLE work_operations (
 scope_id UUID NOT NULL,request_id UUID NOT NULL,actor_id UUID NOT NULL REFERENCES app_users(id),action VARCHAR(50) NOT NULL,
 fingerprint CHAR(64) NOT NULL,work_id UUID NOT NULL REFERENCES work_requests(id),created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(scope_id,request_id)
);
CREATE INDEX work_requests_tenant ON work_requests(tenant_id,status,created_at,id);
CREATE INDEX work_requests_property ON work_requests(property_id,kind,status,created_at,id);
CREATE INDEX work_requests_provider ON work_requests(provider_id,status,created_at,id);
CREATE INDEX work_requests_lease ON work_requests(lease_id,status);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['work_assignments','service_quotes','service_quote_acceptances','work_visits','work_visit_acceptances','work_disputes','work_dispute_resolutions','work_evidence','service_reviews','work_events','work_operations'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
  EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_no_truncate',t);
 END LOOP;
END $$;
ALTER TABLE in_app_notifications ALTER COLUMN application_id DROP NOT NULL;
ALTER TABLE in_app_notifications ADD COLUMN work_request_id UUID REFERENCES work_requests(id);
ALTER TABLE in_app_notifications ADD COLUMN work_event_id UUID REFERENCES work_events(id);
ALTER TABLE in_app_notifications ADD COLUMN work_request_url VARCHAR(250);
ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK
 ((work_event_id IS NOT NULL AND work_request_id IS NOT NULL AND work_request_url IS NOT NULL AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND application_id IS NULL AND lease_id IS NULL) OR
 (work_event_id IS NULL AND work_request_id IS NULL AND application_id IS NOT NULL AND
 ((event_id IS NOT NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND lease_id IS NULL) OR
 (event_id IS NULL AND lease_id IS NOT NULL AND lease_url IS NOT NULL AND ((lease_event_id IS NOT NULL AND billing_event_id IS NULL) OR (lease_event_id IS NULL AND billing_event_id IS NOT NULL))))));
CREATE UNIQUE INDEX notification_work_event_unique ON in_app_notifications(user_id,work_event_id);

CREATE FUNCTION protect_work_request_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.id,OLD.kind,OLD.lease_id,OLD.property_id,OLD.unit_id,OLD.tenant_id,OLD.reporter_id,OLD.owner_organization_id,OLD.category,OLD.title,OLD.description,OLD.country_code,OLD.city,OLD.address,OLD.unit_label,OLD.created_at)
 IS DISTINCT FROM (NEW.id,NEW.kind,NEW.lease_id,NEW.property_id,NEW.unit_id,NEW.tenant_id,NEW.reporter_id,NEW.owner_organization_id,NEW.category,NEW.title,NEW.description,NEW.country_code,NEW.city,NEW.address,NEW.unit_label,NEW.created_at) THEN
  RAISE EXCEPTION 'Work identity and original request content are immutable';
 END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER work_request_identity BEFORE UPDATE ON work_requests FOR EACH ROW EXECUTE FUNCTION protect_work_request_identity();
CREATE TRIGGER work_requests_no_delete BEFORE DELETE ON work_requests FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER work_requests_no_truncate BEFORE TRUNCATE ON work_requests FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
