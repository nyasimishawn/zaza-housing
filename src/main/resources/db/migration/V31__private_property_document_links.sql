CREATE TABLE property_document_links (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id),
 property_id UUID NOT NULL REFERENCES properties(id), document_id UUID NOT NULL REFERENCES vault_documents(id),
 document_version_id UUID NOT NULL REFERENCES vault_document_versions(id),
 actor_id UUID NOT NULL REFERENCES app_users(id), detached BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX property_document_link_active ON property_document_links(organization_id,property_id,document_version_id) WHERE NOT detached;
CREATE INDEX property_document_link_property ON property_document_links(organization_id,property_id,created_at DESC,id);
