-- WS5c (spec §2). key_hash is the SHA-256 of the whole key; the plaintext is shown once and never stored.
CREATE TABLE developer_api_key (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(100) NOT NULL,
    prefix VARCHAR(12) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX developer_api_key_hash_idx ON developer_api_key (key_hash);
CREATE INDEX developer_api_key_org_idx ON developer_api_key (org_id, created_at);
