CREATE TABLE discovery_places (
 id BIGINT PRIMARY KEY,name VARCHAR(200) NOT NULL,country_code CHAR(2) NOT NULL,
 aliases TEXT NOT NULL DEFAULT '',latitude DOUBLE PRECISION NOT NULL CHECK(latitude BETWEEN -85.05112878 AND 85.05112878),
 longitude DOUBLE PRECISION NOT NULL CHECK(longitude BETWEEN -180 AND 180),population BIGINT NOT NULL DEFAULT 0,
 source VARCHAR(100) NOT NULL DEFAULT 'GeoNames CC BY 4.0'
);
CREATE INDEX discovery_places_name ON discovery_places(country_code,lower(name),population DESC);
CREATE TABLE discovery_commands(request_id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),action VARCHAR(40) NOT NULL,target_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL);
CREATE TRIGGER discovery_commands_immutable BEFORE UPDATE OR DELETE ON discovery_commands FOR EACH ROW EXECUTE FUNCTION reject_audit_changes();
CREATE TRIGGER discovery_commands_no_truncate BEFORE TRUNCATE ON discovery_commands FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_changes();
CREATE TABLE discovery_saved_items(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),kind VARCHAR(10) NOT NULL CHECK(kind IN('PROPERTY','PROVIDER')),target_id UUID NOT NULL,folder VARCHAR(80) NOT NULL DEFAULT '',note VARCHAR(1000) NOT NULL DEFAULT '',version BIGINT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,UNIQUE(user_id,kind,target_id));
CREATE INDEX discovery_saved_items_user ON discovery_saved_items(user_id,updated_at DESC,id);
CREATE TABLE discovery_saved_searches(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),name VARCHAR(100) NOT NULL,query JSONB NOT NULL,frequency VARCHAR(10) NOT NULL CHECK(frequency IN('OFF','HOURLY','DAILY')),generation UUID NOT NULL,version BIGINT NOT NULL DEFAULT 0,next_scan_at TIMESTAMPTZ NOT NULL,last_scan_at TIMESTAMPTZ,last_error VARCHAR(100),created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL);
CREATE INDEX discovery_searches_user ON discovery_saved_searches(user_id,updated_at DESC,id);
CREATE INDEX discovery_searches_due ON discovery_saved_searches(next_scan_at,id) WHERE frequency<>'OFF';
CREATE TABLE discovery_search_seen(search_id UUID NOT NULL REFERENCES discovery_saved_searches(id) ON DELETE CASCADE,generation UUID NOT NULL,target_id UUID NOT NULL,PRIMARY KEY(search_id,generation,target_id));
CREATE TABLE discovery_alerts(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),search_id UUID NOT NULL REFERENCES discovery_saved_searches(id) ON DELETE CASCADE,generation UUID NOT NULL,target_id UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL,read_at TIMESTAMPTZ,UNIQUE(search_id,generation,target_id));
CREATE INDEX discovery_alerts_user ON discovery_alerts(user_id,created_at DESC,id);
CREATE TABLE discovery_comparisons(id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id),name VARCHAR(100) NOT NULL,listing_ids JSONB NOT NULL CHECK(jsonb_array_length(listing_ids) BETWEEN 1 AND 4),version BIGINT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL);
CREATE INDEX discovery_comparisons_user ON discovery_comparisons(user_id,updated_at DESC,id);
