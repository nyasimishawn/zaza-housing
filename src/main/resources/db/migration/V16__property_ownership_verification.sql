ALTER TABLE verification_cases ADD COLUMN property_id UUID REFERENCES properties(id);
ALTER TABLE verification_cases ADD COLUMN ownership_subject JSONB;
ALTER TABLE verification_cases ALTER COLUMN kind TYPE VARCHAR(30);
DO $$ DECLARE c RECORD; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='verification_cases'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%kind%' LOOP
        EXECUTE format('ALTER TABLE verification_cases DROP CONSTRAINT %I',c.conname);
    END LOOP;
END $$;
ALTER TABLE verification_cases ADD CONSTRAINT verification_kind_subject CHECK (
    (kind='IDENTITY' AND owner_user_id IS NOT NULL AND organization_id IS NULL AND property_id IS NULL AND ownership_subject IS NULL) OR
    (kind='BUSINESS' AND owner_user_id IS NULL AND organization_id IS NOT NULL AND property_id IS NULL AND ownership_subject IS NULL) OR
    (kind='PROPERTY_OWNERSHIP' AND owner_user_id IS NULL AND organization_id IS NOT NULL AND property_id IS NOT NULL
        AND ownership_subject IS NOT NULL AND jsonb_typeof(ownership_subject)='object')
);
DROP INDEX verification_active_business;
CREATE UNIQUE INDEX verification_active_business ON verification_cases(organization_id)
    WHERE kind='BUSINESS' AND status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED');
CREATE UNIQUE INDEX verification_active_property ON verification_cases(property_id)
    WHERE kind='PROPERTY_OWNERSHIP' AND status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED');
CREATE INDEX verification_property_history ON verification_cases(organization_id,property_id,created_at DESC,id);

CREATE OR REPLACE FUNCTION protect_verification_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.id,OLD.owner_user_id,OLD.organization_id,OLD.kind,OLD.property_id,OLD.ownership_subject,OLD.created_by,OLD.created_at) IS DISTINCT FROM
       (NEW.id,NEW.owner_user_id,NEW.organization_id,NEW.kind,NEW.property_id,NEW.ownership_subject,NEW.created_by,NEW.created_at)
       OR OLD.status IN ('REJECTED','WITHDRAWN','REVOKED','EXPIRED')
       OR (OLD.status='VERIFIED' AND (NEW.status NOT IN ('WITHDRAWN','REVOKED','EXPIRED') OR NEW.valid_until IS DISTINCT FROM OLD.valid_until OR NEW.shared_submission_id IS DISTINCT FROM OLD.shared_submission_id)) THEN
        RAISE EXCEPTION 'Verification subjects, submitted evidence and terminal decisions are protected';
    END IF;
    RETURN NEW;
END;
$$;
