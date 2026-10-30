CREATE TABLE team_invitations (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id),
 email VARCHAR(254) NOT NULL CHECK(email=lower(email)), role VARCHAR(10) NOT NULL CHECK(role IN('ADMIN','MEMBER')),
 invited_by UUID NOT NULL REFERENCES app_users(id), request_id UUID NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
 CHECK(status IN('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, last_sent_at TIMESTAMPTZ NOT NULL, send_count INTEGER NOT NULL DEFAULT 1,
 accepted_by UUID REFERENCES app_users(id), UNIQUE(organization_id,request_id)
);
CREATE UNIQUE INDEX team_invitation_pending ON team_invitations(organization_id,email) WHERE status='PENDING';
CREATE INDEX team_invitation_recipient ON team_invitations(email,created_at DESC,id);
CREATE TABLE team_invitation_events (
 id UUID PRIMARY KEY, invitation_id UUID NOT NULL REFERENCES team_invitations(id),
 actor_id UUID NOT NULL REFERENCES app_users(id), action VARCHAR(30) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TRIGGER team_invitation_events_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON team_invitation_events
 FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();

CREATE TABLE notification_settings (
 user_id UUID PRIMARY KEY REFERENCES app_users(id), version BIGINT NOT NULL DEFAULT 0,
 timezone VARCHAR(80) NOT NULL DEFAULT 'Africa/Nairobi', quiet_start INTEGER CHECK(quiet_start BETWEEN 0 AND 1439),
 quiet_end INTEGER CHECK(quiet_end BETWEEN 0 AND 1439), email_mode VARCHAR(10) NOT NULL DEFAULT 'INSTANT' CHECK(email_mode IN('INSTANT','DAILY')),
 CHECK((quiet_start IS NULL)=(quiet_end IS NULL)), CHECK(quiet_start IS NULL OR quiet_start<>quiet_end)
);
CREATE TABLE notification_preferences (
 user_id UUID NOT NULL REFERENCES app_users(id), category VARCHAR(20) NOT NULL
 CHECK(category IN('TEAM','MESSAGES','RENTAL','BILLING','MAINTENANCE','SERVICES','DOCUMENTS','SUPPORT','DISCOVERY','REMINDERS','GENERAL')),
 in_app BOOLEAN NOT NULL DEFAULT TRUE,email BOOLEAN NOT NULL DEFAULT FALSE,sms BOOLEAN NOT NULL DEFAULT FALSE,push BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(user_id,category)
);
CREATE TABLE notification_phone_contacts (
 user_id UUID PRIMARY KEY REFERENCES app_users(id), encrypted_phone TEXT NOT NULL, phone_hash CHAR(64) NOT NULL,
 code_hash CHAR(64) NOT NULL, expires_at TIMESTAMPTZ NOT NULL, verified_at TIMESTAMPTZ,
 attempts INTEGER NOT NULL DEFAULT 0, sent_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE notification_devices (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users(id), installation_id UUID NOT NULL,
 platform VARCHAR(10) NOT NULL CHECK(platform IN('ANDROID','IOS')), label VARCHAR(80) NOT NULL,
 encrypted_token TEXT NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE,
 session_id UUID REFERENCES auth_sessions(id) ON DELETE SET NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, UNIQUE(user_id,installation_id)
);
CREATE INDEX notification_devices_account ON notification_devices(user_id,active);
CREATE TABLE notification_reminders (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),request_id UUID NOT NULL,
 title VARCHAR(140) NOT NULL, due_at TIMESTAMPTZ NOT NULL, repeat_rule VARCHAR(10) NOT NULL CHECK(repeat_rule IN('NONE','DAILY','WEEKLY')),
 status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','COMPLETED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(user_id,request_id)
);
CREATE INDEX notification_reminders_due ON notification_reminders(due_at) WHERE status='ACTIVE';
CREATE TABLE notification_workspace_events (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),kind VARCHAR(20) NOT NULL,
 target_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL, dedupe_key VARCHAR(200) NOT NULL UNIQUE
);
CREATE TRIGGER notification_workspace_events_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_workspace_events
 FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
ALTER TABLE in_app_notifications ADD COLUMN workspace_event_id UUID REFERENCES notification_workspace_events(id),
 ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'GENERAL', ADD COLUMN archived_at TIMESTAMPTZ;
CREATE UNIQUE INDEX notification_workspace_event ON in_app_notifications(user_id,workspace_event_id) WHERE workspace_event_id IS NOT NULL;
DO $$ DECLARE previous TEXT; BEGIN
 SELECT pg_get_expr(conbin,conrelid) INTO previous FROM pg_constraint WHERE conrelid='in_app_notifications'::regclass AND conname='notification_event_source';
 ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
 EXECUTE format('ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK ((workspace_event_id IS NULL AND (%s)) OR (workspace_event_id IS NOT NULL AND num_nonnulls(event_id,lease_event_id,billing_event_id,work_event_id,service_finance_event_id,conversation_event_id,vault_event_id,caretaker_event_id,caretaker_target_id,caretaker_url,application_id,lease_id,work_request_id,service_payment_account_id,conversation_id,lease_url,work_request_url,service_payment_url,conversation_url,vault_url)=0))',previous);
END $$;
CREATE TABLE notification_deliveries (
 id UUID PRIMARY KEY,user_id UUID REFERENCES app_users(id),notification_id UUID REFERENCES in_app_notifications(id),
 invitation_id UUID REFERENCES team_invitations(id), device_id UUID REFERENCES notification_devices(id),
 channel VARCHAR(10) NOT NULL CHECK(channel IN('EMAIL','SMS','PUSH')),purpose VARCHAR(20) NOT NULL,
 category VARCHAR(20) NOT NULL, encrypted_payload TEXT,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','ACCEPTED','FAILED','CANCELLED','EXPIRED')),
 dedupe_key VARCHAR(250) NOT NULL UNIQUE, attempts INTEGER NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,created_at TIMESTAMPTZ NOT NULL,last_attempt_at TIMESTAMPTZ,
 error_code VARCHAR(40), provider_reference VARCHAR(150)
);
CREATE INDEX notification_deliveries_due ON notification_deliveries(available_at,id) WHERE status='PENDING';
CREATE INDEX notification_deliveries_user ON notification_deliveries(user_id,created_at DESC,id);
CREATE INDEX notification_deliveries_invitation ON notification_deliveries(invitation_id,created_at DESC,id) WHERE invitation_id IS NOT NULL;
CREATE TABLE notification_delivery_attempts (
 id UUID PRIMARY KEY, delivery_id UUID NOT NULL REFERENCES notification_deliveries(id),attempt INTEGER NOT NULL,
 outcome VARCHAR(20) NOT NULL, error_code VARCHAR(40),created_at TIMESTAMPTZ NOT NULL,UNIQUE(delivery_id,attempt)
);
CREATE TRIGGER notification_delivery_attempts_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_delivery_attempts
 FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();

CREATE FUNCTION notification_category(t TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
 SELECT CASE
 WHEN t LIKE 'TEAM_%' THEN 'TEAM' WHEN t LIKE 'DISCOVERY_%' THEN 'DISCOVERY'
 WHEN t LIKE 'REMINDER_%' THEN 'REMINDERS' WHEN t LIKE 'SUPPORT_%' OR t LIKE 'CASE_%' OR t LIKE 'REPORT_%' THEN 'SUPPORT'
 WHEN t LIKE 'CONVERSATION_%' OR t LIKE 'MESSAGE_%' OR t LIKE 'VIEWING_%' THEN 'MESSAGES'
 WHEN t LIKE 'BILLING_%' OR t LIKE 'PAYMENT_%' OR t LIKE 'CHARGE_%' OR t LIKE 'RENT_%' OR t LIKE 'DEPOSIT_%' THEN 'BILLING'
 WHEN t LIKE 'SERVICE_%' THEN 'SERVICES' WHEN t LIKE 'WORK_%' OR t LIKE 'CARETAKER_%' OR t LIKE 'NOTICE_%' THEN 'MAINTENANCE'
 WHEN t LIKE 'VAULT_%' OR t LIKE 'DOCUMENT_%' OR t LIKE 'VERIFICATION_%' OR t LIKE 'OWNERSHIP_%' THEN 'DOCUMENTS'
 WHEN t LIKE 'APPLICATION_%' OR t LIKE 'LEASE_%' OR t LIKE 'INSPECTION_%' THEN 'RENTAL' ELSE 'GENERAL' END
$$;
UPDATE in_app_notifications n SET category=CASE
 WHEN n.work_request_id IS NOT NULL AND EXISTS(SELECT 1 FROM work_requests w WHERE w.id=n.work_request_id AND w.kind='SERVICE_BOOKING') THEN 'SERVICES'
 WHEN notification_category(type)<>'GENERAL' THEN notification_category(type)
 WHEN billing_event_id IS NOT NULL THEN 'BILLING' WHEN service_finance_event_id IS NOT NULL THEN 'SERVICES'
 WHEN conversation_id IS NOT NULL THEN 'MESSAGES' WHEN work_request_id IS NOT NULL OR caretaker_event_id IS NOT NULL THEN 'MAINTENANCE'
 WHEN vault_event_id IS NOT NULL THEN 'DOCUMENTS' WHEN lease_id IS NOT NULL OR application_id IS NOT NULL THEN 'RENTAL' ELSE 'GENERAL' END;
CREATE FUNCTION classify_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 NEW.category=notification_category(NEW.type);
 IF NEW.category='GENERAL' THEN
  NEW.category=CASE WHEN NEW.billing_event_id IS NOT NULL THEN 'BILLING'
   WHEN NEW.service_finance_event_id IS NOT NULL THEN 'SERVICES' WHEN NEW.conversation_id IS NOT NULL THEN 'MESSAGES'
   WHEN NEW.work_request_id IS NOT NULL OR NEW.caretaker_event_id IS NOT NULL THEN 'MAINTENANCE'
   WHEN NEW.vault_event_id IS NOT NULL THEN 'DOCUMENTS' WHEN NEW.lease_id IS NOT NULL OR NEW.application_id IS NOT NULL THEN 'RENTAL' ELSE 'GENERAL' END;
 END IF;
 IF NEW.work_request_id IS NOT NULL AND EXISTS(SELECT 1 FROM work_requests w WHERE w.id=NEW.work_request_id AND w.kind='SERVICE_BOOKING')
 THEN NEW.category='SERVICES';END IF;
 IF EXISTS(SELECT 1 FROM notification_preferences WHERE user_id=NEW.user_id AND category=NEW.category AND NOT in_app)
 THEN NEW.archived_at=now();END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER classify_notification BEFORE INSERT ON in_app_notifications FOR EACH ROW EXECUTE FUNCTION classify_notification();
-- Every existing domain writes through this table, so new alerts are captured atomically without polling or replaying old history.
CREATE FUNCTION queue_notification_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p notification_preferences%ROWTYPE; d RECORD; daily BOOLEAN; due TIMESTAMPTZ; k TEXT; tz TEXT;
BEGIN
 SELECT * INTO p FROM notification_preferences WHERE user_id=NEW.user_id AND category=NEW.category;
 IF NOT FOUND THEN RETURN NEW;END IF;
 SELECT email_mode='DAILY',timezone INTO daily,tz FROM notification_settings WHERE user_id=NEW.user_id;
 due=now();
 IF p.email AND EXISTS(SELECT 1 FROM app_users WHERE id=NEW.user_id AND active AND email_verified_at IS NOT NULL) THEN
  IF coalesce(daily,FALSE) THEN
   due=(date_trunc('day',now() AT TIME ZONE coalesce(tz,'Africa/Nairobi'))+interval '1 day 8 hours') AT TIME ZONE coalesce(tz,'Africa/Nairobi');
   k='digest:'||NEW.user_id||':'||to_char(now() AT TIME ZONE coalesce(tz,'Africa/Nairobi'),'YYYY-MM-DD');
  ELSE k=NEW.id||':EMAIL';END IF;
  INSERT INTO notification_deliveries(id,user_id,notification_id,channel,purpose,category,dedupe_key,available_at,expires_at,created_at)
  VALUES(gen_random_uuid(),NEW.user_id,CASE WHEN coalesce(daily,FALSE) THEN NULL ELSE NEW.id END,'EMAIL',CASE WHEN coalesce(daily,FALSE) THEN 'DIGEST' ELSE 'ALERT' END,NEW.category,k,due,due+interval '3 days',now()) ON CONFLICT(dedupe_key) DO NOTHING;
 END IF;
 IF p.sms AND EXISTS(SELECT 1 FROM notification_phone_contacts WHERE user_id=NEW.user_id AND verified_at IS NOT NULL) THEN
  INSERT INTO notification_deliveries(id,user_id,notification_id,channel,purpose,category,dedupe_key,available_at,expires_at,created_at)
  VALUES(gen_random_uuid(),NEW.user_id,NEW.id,'SMS','ALERT',NEW.category,NEW.id||':SMS',now(),now()+interval '3 days',now());
 END IF;
 IF p.push THEN
  FOR d IN SELECT id FROM notification_devices WHERE user_id=NEW.user_id AND active LOOP
   INSERT INTO notification_deliveries(id,user_id,notification_id,device_id,channel,purpose,category,dedupe_key,available_at,expires_at,created_at)
   VALUES(gen_random_uuid(),NEW.user_id,NEW.id,d.id,'PUSH','ALERT',NEW.category,NEW.id||':PUSH:'||d.id,now(),now()+interval '3 days',now());
  END LOOP;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER queue_notification_delivery AFTER INSERT ON in_app_notifications FOR EACH ROW EXECUTE FUNCTION queue_notification_delivery();

CREATE FUNCTION discovery_notification_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event UUID; BEGIN
 event=gen_random_uuid();
 INSERT INTO notification_workspace_events(id,user_id,kind,target_id,created_at,dedupe_key)
 VALUES(event,NEW.user_id,'DISCOVERY',NEW.search_id,NEW.created_at,'discovery:'||NEW.id);
 INSERT INTO in_app_notifications(id,user_id,workspace_event_id,type,message)
 VALUES(gen_random_uuid(),NEW.user_id,event,'DISCOVERY_MATCH','A saved search has a new match. Open your saved searches to review it.');
 RETURN NEW;
END $$;
CREATE TRIGGER discovery_notification_delivery AFTER INSERT ON discovery_alerts FOR EACH ROW EXECUTE FUNCTION discovery_notification_delivery();

CREATE FUNCTION support_notification_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event UUID; BEGIN
 event=gen_random_uuid();
 INSERT INTO notification_workspace_events(id,user_id,kind,target_id,created_at,dedupe_key)
 VALUES(event,NEW.user_id,'SUPPORT',NEW.case_id,NEW.created_at,'support:'||NEW.id);
 INSERT INTO in_app_notifications(id,user_id,workspace_event_id,type,message)
 VALUES(gen_random_uuid(),NEW.user_id,event,'SUPPORT_CASE_UPDATED','A support case you follow was updated. Open your private support updates for details.');
 RETURN NEW;
END $$;
CREATE TRIGGER support_notification_delivery AFTER INSERT ON support_notifications FOR EACH ROW EXECUTE FUNCTION support_notification_delivery();
