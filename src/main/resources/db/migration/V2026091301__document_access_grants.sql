CREATE TABLE document_access_grants (
    grant_key VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX document_access_grants_expiry_idx ON document_access_grants(expires_at);
