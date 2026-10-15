CREATE FUNCTION validate_service_account_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (
 SELECT 1 FROM work_requests w JOIN service_quotes q ON q.id=NEW.quote_id AND q.work_id=w.id
 JOIN work_assignments a ON a.id=NEW.assignment_id AND a.work_id=w.id AND a.provider_id=NEW.provider_id
 JOIN service_quote_acceptances c ON c.quote_id=q.id
 WHERE w.id=NEW.work_id AND w.assignment_id=a.id AND w.quote_id=q.id AND q.assignment_id=a.id
 AND q.amount=NEW.amount AND q.currency=NEW.currency AND w.provider_id=NEW.provider_id
 AND ((w.kind='SERVICE_BOOKING' AND NEW.payer_user_id=w.tenant_id AND NEW.payer_organization_id IS NULL)
 OR (w.kind='MAINTENANCE' AND NEW.payer_organization_id=w.owner_organization_id AND NEW.payer_user_id IS NULL))
 AND NEW.fee=round(NEW.amount*NEW.commission_bps/10000,2))
 THEN RAISE EXCEPTION 'Service financial account must match accepted work and quote evidence'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER service_account_source BEFORE INSERT ON service_payment_accounts FOR EACH ROW EXECUTE FUNCTION validate_service_account_source();
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['service_payment_accounts','service_payment_claims','service_claim_decisions','service_transfer_references',
 'service_payment_receipts','service_receipt_voids','service_payment_refunds','provider_payout_destinations','provider_destination_decisions',
 'provider_payout_requests','provider_payout_decisions','service_finance_operations','service_finance_events']
 LOOP EXECUTE format('CREATE TRIGGER no_truncate_service_finance BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION protect_service_finance_immutable()',t); END LOOP;
END $$;
CREATE INDEX service_finance_event_account ON service_finance_events(account_id,created_at,id);
CREATE INDEX service_receipt_account ON service_payment_receipts(account_id,created_at,id);
CREATE INDEX service_claim_account ON service_payment_claims(account_id,created_at,id);
CREATE INDEX service_refund_account ON service_payment_refunds(account_id,receipt_id,created_at,id);
CREATE INDEX provider_destination_provider ON provider_payout_destinations(provider_id,created_at,id);
CREATE INDEX provider_destination_history ON provider_destination_decisions(destination_id,sequence DESC);
CREATE INDEX provider_payout_account ON provider_payout_requests(account_id,created_at,id);
CREATE INDEX provider_payout_history ON provider_payout_decisions(payout_id,sequence DESC);
CREATE INDEX service_payment_work ON service_payment_accounts(work_id);
ALTER TABLE service_payment_claims ADD UNIQUE(id,account_id);
ALTER TABLE service_payment_receipts ADD UNIQUE(id,account_id);
ALTER TABLE service_payment_receipts ADD UNIQUE(channel,reference);
ALTER TABLE service_payment_receipts ADD FOREIGN KEY(claim_id,account_id) REFERENCES service_payment_claims(id,account_id);
ALTER TABLE service_payment_refunds ADD FOREIGN KEY(receipt_id,account_id) REFERENCES service_payment_receipts(id,account_id);
ALTER TABLE provider_payout_decisions ADD CONSTRAINT provider_transfer_evidence CHECK
 ((status='PAID' AND num_nonnulls(channel,reference,paid_on)=3) OR
 (status<>'PAID' AND num_nonnulls(channel,reference,paid_on)=0));

CREATE OR REPLACE VIEW provider_payout_status AS
 SELECT p.*,a.currency,coalesce(d.status,'REQUESTED') AS status,d.reference,d.paid_on,
 (SELECT x.actor_id FROM provider_payout_decisions x WHERE x.payout_id=p.id AND x.status='APPROVED' ORDER BY sequence DESC LIMIT 1) AS approved_by,
 d.reason AS decision_reason
 FROM provider_payout_requests p JOIN service_payment_accounts a ON a.id=p.account_id
 LEFT JOIN LATERAL (SELECT * FROM provider_payout_decisions d WHERE d.payout_id=p.id ORDER BY sequence DESC LIMIT 1) d ON true;
