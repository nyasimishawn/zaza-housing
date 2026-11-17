-- Addenda never replace an executed revision or its signatures.
CREATE TABLE lease_changes (
 id UUID PRIMARY KEY, lease_id UUID NOT NULL REFERENCES rental_leases(id), revision_id UUID NOT NULL,
 kind VARCHAR(15) NOT NULL CHECK(kind IN ('RENEWAL','AMENDMENT')),
 effective_date DATE NOT NULL, end_date DATE NOT NULL CHECK(end_date>effective_date),
 monthly_rent NUMERIC(14,2) NOT NULL CHECK(monthly_rent>=0), agreement_text TEXT NOT NULL,
 requester_id UUID NOT NULL REFERENCES app_users(id), requester_party VARCHAR(10) NOT NULL CHECK(requester_party IN ('TENANT','LESSOR')),
 document TEXT NOT NULL, document_hash CHAR(64) NOT NULL CHECK(document_hash ~ '^[0-9a-f]{64}$'),
 status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(lease_id,revision_id) REFERENCES lease_revisions(lease_id,id)
);
CREATE UNIQUE INDEX lease_one_pending_change ON lease_changes(lease_id) WHERE status='PENDING';
CREATE INDEX lease_accepted_changes ON lease_changes(lease_id,effective_date) WHERE status='ACCEPTED';
CREATE TABLE lease_change_consents (
 id UUID PRIMARY KEY, change_id UUID NOT NULL REFERENCES lease_changes(id),
 party VARCHAR(10) NOT NULL CHECK(party IN ('TENANT','LESSOR')), signer_id UUID NOT NULL REFERENCES app_users(id),
 typed_name VARCHAR(150) NOT NULL, email VARCHAR(254) NOT NULL, document_hash CHAR(64) NOT NULL,
 method VARCHAR(50) NOT NULL DEFAULT 'ACCOUNT_PASSWORD_CONSENT', signed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(change_id,party), UNIQUE(change_id,signer_id)
);
CREATE FUNCTION protect_lease_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (to_jsonb(OLD)-'status') IS DISTINCT FROM (to_jsonb(NEW)-'status') OR OLD.status<>'PENDING' THEN
  RAISE EXCEPTION 'Lease change content and resolved decisions are immutable';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER lease_changes_protect BEFORE UPDATE ON lease_changes FOR EACH ROW EXECUTE FUNCTION protect_lease_change();
CREATE TRIGGER lease_changes_retain BEFORE DELETE OR TRUNCATE ON lease_changes FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_change_consents_immutable BEFORE UPDATE OR DELETE ON lease_change_consents FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER lease_change_consents_retain BEFORE TRUNCATE ON lease_change_consents FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
-- Replacement schedules credit old invoices, retaining the complete original ledger.
DROP INDEX billing_rent_period_unique;
CREATE INDEX billing_rent_period_idx ON billing_invoices(lease_id,period_start) WHERE kind='RENT';
CREATE TABLE billing_rent_supersessions (
 invoice_id UUID PRIMARY KEY REFERENCES billing_invoices(id), change_id UUID NOT NULL REFERENCES lease_changes(id)
);
CREATE TRIGGER billing_rent_supersessions_immutable BEFORE UPDATE OR DELETE ON billing_rent_supersessions FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
