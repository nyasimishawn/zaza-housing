CREATE TABLE service_payment_accounts (
 id UUID PRIMARY KEY, work_id UUID NOT NULL REFERENCES work_requests(id),
 assignment_id UUID NOT NULL REFERENCES work_assignments(id), quote_id UUID NOT NULL UNIQUE REFERENCES service_quotes(id),
 provider_id UUID NOT NULL REFERENCES organizations(id), payer_user_id UUID REFERENCES app_users(id),
 payer_organization_id UUID REFERENCES organizations(id), currency CHAR(3) NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK(amount>=0), commission_bps INTEGER NOT NULL CHECK(commission_bps BETWEEN 0 AND 5000),
 fee NUMERIC(14,2) NOT NULL CHECK(fee>=0 AND fee<=amount),
 status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(num_nonnulls(payer_user_id,payer_organization_id)=1)
);
CREATE UNIQUE INDEX service_payment_one_open_assignment ON service_payment_accounts(assignment_id) WHERE status='OPEN';
CREATE INDEX service_payment_provider ON service_payment_accounts(provider_id,created_at,id);
CREATE INDEX service_payment_payer ON service_payment_accounts(payer_user_id,payer_organization_id,created_at,id);
CREATE TABLE service_payment_claims (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES service_payment_accounts(id),
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0), channel VARCHAR(24) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CARD_TERMINAL')),
 reference VARCHAR(120) NOT NULL, received_on DATE NOT NULL, reason VARCHAR(2000) NOT NULL,
 reported_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE service_claim_decisions (
 claim_id UUID PRIMARY KEY REFERENCES service_payment_claims(id), accepted BOOLEAN NOT NULL,
 actor_id UUID NOT NULL REFERENCES app_users(id), reason VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE service_transfer_references (
 channel VARCHAR(24) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CARD_TERMINAL')),
 reference VARCHAR(120) NOT NULL CHECK(reference=upper(reference)), PRIMARY KEY(channel,reference)
);
CREATE TABLE service_payment_receipts (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES service_payment_accounts(id),
 claim_id UUID UNIQUE REFERENCES service_payment_claims(id), amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 channel VARCHAR(24) NOT NULL, reference VARCHAR(120) NOT NULL, received_on DATE NOT NULL,
 verified_by UUID NOT NULL REFERENCES app_users(id), reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), FOREIGN KEY(channel,reference) REFERENCES service_transfer_references(channel,reference)
);
CREATE TABLE service_receipt_voids (
 receipt_id UUID PRIMARY KEY REFERENCES service_payment_receipts(id), actor_id UUID NOT NULL REFERENCES app_users(id),
 reason VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE service_payment_refunds (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES service_payment_accounts(id),
 receipt_id UUID NOT NULL REFERENCES service_payment_receipts(id), amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 channel VARCHAR(24) NOT NULL, reference VARCHAR(120) NOT NULL, paid_on DATE NOT NULL,
 verified_by UUID NOT NULL REFERENCES app_users(id), reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), FOREIGN KEY(channel,reference) REFERENCES service_transfer_references(channel,reference)
);
-- Store an opaque beneficiary identifier maintained by the external bank/payment system.
-- No account numbers, card data, secrets or unverified self-asserted ownership are stored here.
CREATE TABLE provider_payout_destinations (
 id UUID PRIMARY KEY, provider_id UUID NOT NULL REFERENCES organizations(id),
 channel VARCHAR(24) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY')),
 beneficiary_token VARCHAR(120) NOT NULL, masked_label VARCHAR(120) NOT NULL, currency CHAR(3) NOT NULL,
 created_by UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE provider_destination_decisions (
 id UUID PRIMARY KEY, destination_id UUID NOT NULL REFERENCES provider_payout_destinations(id),
 status VARCHAR(12) NOT NULL CHECK(status IN ('VERIFIED','REVOKED')),
 actor_id UUID NOT NULL REFERENCES app_users(id), reason VARCHAR(2000) NOT NULL,
 sequence BIGSERIAL UNIQUE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE provider_payout_requests (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES service_payment_accounts(id),
 destination_id UUID NOT NULL REFERENCES provider_payout_destinations(id),
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0), requested_by UUID NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE provider_payout_decisions (
 id UUID PRIMARY KEY, payout_id UUID NOT NULL REFERENCES provider_payout_requests(id),
 status VARCHAR(12) NOT NULL CHECK(status IN ('APPROVED','REJECTED','CANCELLED','FAILED','PAID')),
 actor_id UUID NOT NULL REFERENCES app_users(id), reason VARCHAR(2000) NOT NULL,
 channel VARCHAR(24), reference VARCHAR(120), paid_on DATE,
 sequence BIGSERIAL UNIQUE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((status='PAID')=(reference IS NOT NULL AND channel IS NOT NULL AND paid_on IS NOT NULL)),
 FOREIGN KEY(channel,reference) REFERENCES service_transfer_references(channel,reference)
);
CREATE UNIQUE INDEX provider_payout_one_paid ON provider_payout_decisions(payout_id) WHERE status='PAID';
CREATE TABLE service_finance_operations (
 scope_id UUID NOT NULL, request_id UUID NOT NULL, actor_id UUID NOT NULL REFERENCES app_users(id),
 action VARCHAR(50) NOT NULL, fingerprint CHAR(64) NOT NULL, entity_id UUID NOT NULL,
 PRIMARY KEY(scope_id,request_id)
);
CREATE TABLE service_finance_events (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES service_payment_accounts(id),
 actor_id UUID NOT NULL REFERENCES app_users(id), type VARCHAR(50) NOT NULL,
 entity_id UUID NOT NULL, amount NUMERIC(14,2), message VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE in_app_notifications ADD COLUMN service_finance_event_id UUID REFERENCES service_finance_events(id);
ALTER TABLE in_app_notifications ADD COLUMN service_payment_account_id UUID REFERENCES service_payment_accounts(id);
ALTER TABLE in_app_notifications ADD COLUMN service_payment_url TEXT;
ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK (

 (service_finance_event_id IS NOT NULL AND service_payment_account_id IS NOT NULL AND service_payment_url IS NOT NULL
 AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND work_event_id IS NULL
 AND application_id IS NULL AND lease_id IS NULL AND work_request_id IS NULL) OR
 (service_finance_event_id IS NULL AND service_payment_account_id IS NULL AND service_payment_url IS NULL AND
 ((work_event_id IS NOT NULL AND work_request_id IS NOT NULL AND work_request_url IS NOT NULL AND event_id IS NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND application_id IS NULL AND lease_id IS NULL) OR
 (work_event_id IS NULL AND work_request_id IS NULL AND application_id IS NOT NULL AND
 ((event_id IS NOT NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND lease_id IS NULL) OR
 (event_id IS NULL AND lease_id IS NOT NULL AND lease_url IS NOT NULL AND ((lease_event_id IS NOT NULL AND billing_event_id IS NULL) OR (lease_event_id IS NULL AND billing_event_id IS NOT NULL)))))))
);
CREATE UNIQUE INDEX notification_service_finance_unique ON in_app_notifications(user_id,service_finance_event_id);

CREATE FUNCTION protect_service_finance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Service financial evidence is append-only'; END $$;
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['service_payment_claims','service_claim_decisions','service_transfer_references',
 'service_payment_receipts','service_receipt_voids','service_payment_refunds','provider_payout_destinations',
 'provider_destination_decisions','provider_payout_requests','provider_payout_decisions','service_finance_operations','service_finance_events']
 LOOP EXECUTE format('CREATE TRIGGER immutable_service_finance BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_service_finance_immutable()',t); END LOOP;
END $$;
CREATE FUNCTION protect_service_account_terms() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.work_id,NEW.assignment_id,NEW.quote_id,NEW.provider_id,NEW.payer_user_id,NEW.payer_organization_id,NEW.currency,NEW.amount,NEW.commission_bps,NEW.fee,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.work_id,OLD.assignment_id,OLD.quote_id,OLD.provider_id,OLD.payer_user_id,OLD.payer_organization_id,OLD.currency,OLD.amount,OLD.commission_bps,OLD.fee,OLD.created_at)
 THEN RAISE EXCEPTION 'Service account source terms are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER service_account_terms BEFORE UPDATE ON service_payment_accounts FOR EACH ROW EXECUTE FUNCTION protect_service_account_terms();

CREATE VIEW provider_payout_status AS
 SELECT p.*,a.currency,coalesce(d.status,'REQUESTED') AS status,d.reference,d.paid_on,
 (SELECT x.actor_id FROM provider_payout_decisions x WHERE x.payout_id=p.id AND x.status='APPROVED' ORDER BY sequence DESC LIMIT 1) AS approved_by
 FROM provider_payout_requests p JOIN service_payment_accounts a ON a.id=p.account_id
 LEFT JOIN LATERAL (SELECT * FROM provider_payout_decisions d WHERE d.payout_id=p.id ORDER BY sequence DESC LIMIT 1) d ON true;
CREATE VIEW service_account_balances AS
 SELECT a.id,
 coalesce(r.received,0) AS received,coalesce(f.refunded,0) AS refunded,
 coalesce(p.reserved,0) AS reserved,coalesce(p.paid,0) AS paid,
 CASE WHEN coalesce(p.paid,0)>0 THEN a.fee ELSE 0 END AS fees_earned
 FROM service_payment_accounts a
 LEFT JOIN (SELECT r.account_id,sum(r.amount) AS received FROM service_payment_receipts r
 WHERE NOT EXISTS(SELECT 1 FROM service_receipt_voids v WHERE v.receipt_id=r.id) GROUP BY r.account_id) r ON r.account_id=a.id
 LEFT JOIN (SELECT account_id,sum(amount) AS refunded FROM service_payment_refunds GROUP BY account_id) f ON f.account_id=a.id
 LEFT JOIN (SELECT account_id,sum(CASE WHEN status IN ('REQUESTED','APPROVED') THEN amount ELSE 0 END) AS reserved,
 sum(CASE WHEN status='PAID' THEN amount ELSE 0 END) AS paid FROM provider_payout_status GROUP BY account_id) p ON p.account_id=a.id;

CREATE TRIGGER service_account_no_delete BEFORE DELETE ON service_payment_accounts FOR EACH ROW EXECUTE FUNCTION protect_service_finance_immutable();
