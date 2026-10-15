CREATE FUNCTION protect_billing_account_terms() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.lease_id,OLD.revision_id,OLD.currency,OLD.policy,OLD.created_at) IS DISTINCT FROM
    (NEW.lease_id,NEW.revision_id,NEW.currency,NEW.policy,NEW.created_at)
    OR (OLD.status='FINALIZED' AND NEW.status<>'FINALIZED') THEN
  RAISE EXCEPTION 'Billing source terms and finalization are immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER billing_accounts_protect BEFORE UPDATE ON billing_accounts FOR EACH ROW EXECUTE FUNCTION protect_billing_account_terms();
CREATE TRIGGER billing_accounts_no_delete BEFORE DELETE ON billing_accounts FOR EACH ROW EXECUTE FUNCTION reject_application_history_changes();
CREATE TRIGGER billing_accounts_no_truncate BEFORE TRUNCATE ON billing_accounts FOR EACH STATEMENT EXECUTE FUNCTION reject_application_history_changes();
