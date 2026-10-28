CREATE TABLE administration_commands (
 request_id UUID PRIMARY KEY,actor_id UUID NOT NULL REFERENCES app_users(id),action VARCHAR(60) NOT NULL,
 target_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE administration_events (
 id UUID PRIMARY KEY,actor_id UUID NOT NULL REFERENCES app_users(id),target_kind VARCHAR(20) NOT NULL,
 target_id UUID NOT NULL,action VARCHAR(60) NOT NULL,message VARCHAR(4000) NOT NULL,
 internal BOOLEAN NOT NULL DEFAULT FALSE,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX administration_events_target ON administration_events(target_kind,target_id,created_at,id);
CREATE TABLE account_action_requests (
 id UUID PRIMARY KEY,target_user_id UUID NOT NULL REFERENCES app_users(id),target_version BIGINT NOT NULL,
 action VARCHAR(12) NOT NULL CHECK(action IN('RESTRICT','REACTIVATE')),
 reason VARCHAR(2000) NOT NULL,requested_by UUID NOT NULL REFERENCES app_users(id),
 reviewed_by UUID REFERENCES app_users(id),decision_reason VARCHAR(2000),
 status VARCHAR(12) NOT NULL CHECK(status IN('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 version BIGINT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL,reviewed_at TIMESTAMPTZ,
 CHECK(requested_by<>target_user_id),CHECK(reviewed_by IS NULL OR (reviewed_by<>requested_by AND reviewed_by<>target_user_id))
);
CREATE UNIQUE INDEX one_pending_account_action ON account_action_requests(target_user_id) WHERE status='PENDING';
CREATE TABLE listing_enforcements (
 listing_id UUID PRIMARY KEY REFERENCES rental_listings(id),status VARCHAR(18) NOT NULL CHECK(status IN('REVIEW_REQUESTED','APPROVED','REJECTED','SUSPENDED')),
 assigned_to UUID REFERENCES app_users(id),listing_version BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,public_snapshot JSONB NOT NULL,reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE support_cases (
 id UUID PRIMARY KEY,reporter_id UUID NOT NULL REFERENCES app_users(id),
 kind VARCHAR(15) NOT NULL CHECK(kind IN('SUPPORT','DISPUTE','LISTING_REPORT')),
 category VARCHAR(15) NOT NULL CHECK(category IN('ACCOUNT','RENTAL','MAINTENANCE','PAYMENTS','SAFETY','OTHER')),
 title VARCHAR(150) NOT NULL,description VARCHAR(4000) NOT NULL,listing_id UUID REFERENCES rental_listings(id),
 public_snapshot JSONB,status VARCHAR(20) NOT NULL CHECK(status IN('NEW','IN_PROGRESS','WAITING_CUSTOMER','ESCALATED','RESOLVED','CLOSED')),
 priority VARCHAR(10) NOT NULL CHECK(priority IN('LOW','NORMAL','HIGH','URGENT')),assigned_to UUID REFERENCES app_users(id),
 version BIGINT NOT NULL DEFAULT 0,response_due_at TIMESTAMPTZ NOT NULL,resolution_due_at TIMESTAMPTZ NOT NULL,
 first_response_at TIMESTAMPTZ,resolution VARCHAR(4000),resolved_at TIMESTAMPTZ,closed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 CHECK(assigned_to IS NULL OR assigned_to<>reporter_id),CHECK(kind<>'LISTING_REPORT' OR listing_id IS NOT NULL),
 CHECK(status NOT IN('RESOLVED','CLOSED') OR (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX support_cases_reporter ON support_cases(reporter_id,updated_at DESC,id);
CREATE INDEX support_cases_queue ON support_cases(status,priority,response_due_at,id);
CREATE TABLE support_messages (
 id UUID PRIMARY KEY,case_id UUID NOT NULL REFERENCES support_cases(id),author_id UUID NOT NULL REFERENCES app_users(id),
 body VARCHAR(4000) NOT NULL,internal BOOLEAN NOT NULL DEFAULT FALSE,created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX support_messages_case ON support_messages(case_id,created_at,id);
CREATE TABLE support_evidence (
 id UUID PRIMARY KEY,case_id UUID NOT NULL REFERENCES support_cases(id),author_id UUID NOT NULL REFERENCES app_users(id),
 caption VARCHAR(200) NOT NULL,internal BOOLEAN NOT NULL DEFAULT FALSE,storage_key VARCHAR(50) NOT NULL UNIQUE,
 key_id VARCHAR(40) NOT NULL,envelope_prefix VARCHAR(60) NOT NULL,content_type VARCHAR(50) NOT NULL,
 width INTEGER NOT NULL,height INTEGER NOT NULL,byte_size BIGINT NOT NULL,cipher_size BIGINT NOT NULL,sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE support_notifications (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),case_id UUID NOT NULL REFERENCES support_cases(id),
 message VARCHAR(200) NOT NULL,created_at TIMESTAMPTZ NOT NULL,read_at TIMESTAMPTZ
);
CREATE INDEX support_notifications_user ON support_notifications(user_id,created_at DESC,id);
CREATE TABLE operational_settings (
 key VARCHAR(60) PRIMARY KEY,value INTEGER NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 updated_by UUID REFERENCES app_users(id),updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO operational_settings(key,value) VALUES('support.firstResponseHours',24),('support.resolutionHours',72);
CREATE FUNCTION protect_administration_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME IN('administration_commands','administration_events','support_messages','support_evidence') THEN RAISE EXCEPTION 'Operational evidence is append-only';END IF;
 IF TG_TABLE_NAME='support_cases' THEN
  IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Support cases cannot be removed';END IF;
  IF (OLD.id,OLD.reporter_id,OLD.kind,OLD.category,OLD.title,OLD.description,OLD.listing_id,OLD.public_snapshot,OLD.created_at)
   IS DISTINCT FROM (NEW.id,NEW.reporter_id,NEW.kind,NEW.category,NEW.title,NEW.description,NEW.listing_id,NEW.public_snapshot,NEW.created_at)
   THEN RAISE EXCEPTION 'Original support reports are immutable';END IF;
  IF OLD.status='CLOSED' THEN RAISE EXCEPTION 'Closed support cases are immutable';END IF;
 END IF;
 IF TG_TABLE_NAME='account_action_requests' THEN
  IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Account decisions cannot be removed';END IF;
  IF (OLD.id,OLD.target_user_id,OLD.target_version,OLD.action,OLD.reason,OLD.requested_by,OLD.created_at)
   IS DISTINCT FROM (NEW.id,NEW.target_user_id,NEW.target_version,NEW.action,NEW.reason,NEW.requested_by,NEW.created_at)
   THEN RAISE EXCEPTION 'Account action proposals are immutable';END IF;
  IF OLD.status<>'PENDING' THEN RAISE EXCEPTION 'Reviewed account decisions are immutable';END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t TEXT;BEGIN
 FOREACH t IN ARRAY ARRAY['administration_commands','administration_events','support_messages','support_evidence','support_cases','account_action_requests'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_administration_record()',t||'_history',t);
  EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_changes()',t||'_no_truncate',t);
 END LOOP;
END $$;
