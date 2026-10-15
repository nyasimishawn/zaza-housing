CREATE TABLE billing_accounts (
 lease_id UUID PRIMARY KEY REFERENCES rental_leases(id), revision_id UUID NOT NULL,
 currency CHAR(3) NOT NULL, policy VARCHAR(40) NOT NULL CHECK(policy='CALENDAR_ACTUAL_DAYS'),
 status VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','FINALIZED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(lease_id,revision_id) REFERENCES lease_revisions(lease_id,id)
);
CREATE TABLE billing_invoices (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id),
 kind VARCHAR(15) NOT NULL CHECK(kind IN ('RENT','DEPOSIT','CHARGE')), description VARCHAR(2000) NOT NULL,
 due_date DATE NOT NULL, period_start DATE, period_end DATE, amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(lease_id,id),
 CHECK((kind='RENT' AND period_start IS NOT NULL AND period_end>period_start) OR (kind<>'RENT' AND period_start IS NULL AND period_end IS NULL))
);
CREATE UNIQUE INDEX billing_rent_period_unique ON billing_invoices(lease_id,period_start) WHERE kind='RENT';
CREATE UNIQUE INDEX billing_deposit_unique ON billing_invoices(lease_id) WHERE kind='DEPOSIT';
CREATE TABLE billing_credits (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL, invoice_id UUID NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 reason VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(lease_id,invoice_id) REFERENCES billing_invoices(lease_id,id)
);
CREATE TABLE payment_claims (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), actor_id UUID NOT NULL REFERENCES app_users(id),
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0), channel VARCHAR(30) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CARD_TERMINAL')),
 reference VARCHAR(120) NOT NULL, received_on DATE NOT NULL, reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(lease_id,id)
);
CREATE TABLE payment_claim_decisions (
 claim_id UUID PRIMARY KEY REFERENCES payment_claims(id), accepted BOOLEAN NOT NULL, reason VARCHAR(2000) NOT NULL,
 actor_id UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- References are unique across all leases of the receiving owner, including voided receipts.
CREATE TABLE payment_references (
 owner_organization_id UUID NOT NULL REFERENCES organizations(id), channel VARCHAR(30) NOT NULL,
 reference VARCHAR(120) NOT NULL, PRIMARY KEY(owner_organization_id,channel,reference)
);
CREATE TABLE payment_receipts (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), claim_id UUID UNIQUE,
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0), channel VARCHAR(30) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CARD_TERMINAL')),
 reference VARCHAR(120) NOT NULL, received_on DATE NOT NULL, verified_by UUID NOT NULL REFERENCES app_users(id),
 reason VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(lease_id,id),
 FOREIGN KEY(lease_id,claim_id) REFERENCES payment_claims(lease_id,id)
);
CREATE TABLE payment_voids (
 payment_id UUID PRIMARY KEY REFERENCES payment_receipts(id), reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE payment_allocations (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL, payment_id UUID NOT NULL, invoice_id UUID NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(lease_id,payment_id) REFERENCES payment_receipts(lease_id,id), FOREIGN KEY(lease_id,invoice_id) REFERENCES billing_invoices(lease_id,id), UNIQUE(lease_id,id)
);
CREATE TABLE payment_allocation_reversals (
 allocation_id UUID PRIMARY KEY REFERENCES payment_allocations(id), reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE deposit_settlements (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), held NUMERIC(14,2) NOT NULL CHECK(held>0),
 deduction NUMERIC(14,2) NOT NULL CHECK(deduction>=0 AND deduction<=held), reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(lease_id,id)
);
CREATE TABLE deposit_settlement_decisions (
 settlement_id UUID PRIMARY KEY REFERENCES deposit_settlements(id), decision VARCHAR(15) NOT NULL CHECK(decision IN ('ACCEPT','DISPUTE','CANCEL')),
 reason VARCHAR(2000) NOT NULL, actor_id UUID NOT NULL REFERENCES app_users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE payment_refunds (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), payment_id UUID,
 deposit BOOLEAN NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 channel VARCHAR(30) NOT NULL CHECK(channel IN ('BANK_TRANSFER','MOBILE_MONEY','CASH','CARD_TERMINAL')),
 reference VARCHAR(120) NOT NULL, paid_on DATE NOT NULL, reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((deposit AND payment_id IS NULL) OR (NOT deposit AND payment_id IS NOT NULL)),
 FOREIGN KEY(lease_id,payment_id) REFERENCES payment_receipts(lease_id,id)
);
CREATE TABLE billing_events (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), type VARCHAR(40) NOT NULL,
 entity_id UUID NOT NULL, actor_id UUID NOT NULL REFERENCES app_users(id), message VARCHAR(2000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE billing_operations (
 lease_id UUID NOT NULL REFERENCES billing_accounts(lease_id), request_id UUID NOT NULL,
 actor_id UUID NOT NULL REFERENCES app_users(id), action VARCHAR(40) NOT NULL, fingerprint CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(lease_id,request_id)
);
CREATE INDEX billing_invoices_due ON billing_invoices(lease_id,due_date,id);
CREATE INDEX payment_receipts_lease ON payment_receipts(lease_id,created_at,id);
CREATE INDEX payment_claims_lease ON payment_claims(lease_id,created_at,id);
CREATE INDEX payment_allocations_lease ON payment_allocations(lease_id,invoice_id,payment_id);
CREATE INDEX billing_events_lease ON billing_events(lease_id,created_at,id);
CREATE INDEX billing_credits_invoice ON billing_credits(invoice_id);
CREATE INDEX payment_refunds_lease ON payment_refunds(lease_id,payment_id);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['billing_invoices','billing_credits','payment_claims','payment_claim_decisions','payment_references','payment_receipts','payment_voids','payment_allocations','payment_allocation_reversals','deposit_settlements','deposit_settlement_decisions','payment_refunds','billing_events','billing_operations'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes()',t||'_immutable',t);
  EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes()',t||'_no_truncate',t);
 END LOOP;
END $$;
ALTER TABLE in_app_notifications ADD COLUMN billing_event_id UUID REFERENCES billing_events(id);
ALTER TABLE in_app_notifications DROP CONSTRAINT notification_event_source;
ALTER TABLE in_app_notifications ADD CONSTRAINT notification_event_source CHECK
 ((event_id IS NOT NULL AND lease_event_id IS NULL AND billing_event_id IS NULL AND lease_id IS NULL) OR
 (event_id IS NULL AND lease_id IS NOT NULL AND lease_url IS NOT NULL AND
  ((lease_event_id IS NOT NULL AND billing_event_id IS NULL) OR (lease_event_id IS NULL AND billing_event_id IS NOT NULL))));
CREATE UNIQUE INDEX notification_billing_event_unique ON in_app_notifications(user_id,billing_event_id);
