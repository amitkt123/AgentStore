CREATE TABLE agent (
    id UUID PRIMARY KEY,
    developer_org_id UUID NOT NULL REFERENCES org(id),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_version (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    version_number VARCHAR(50) NOT NULL,
    manifest_json JSONB NOT NULL,
    image_ref VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    tier VARCHAR(10),
    changelog TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    UNIQUE (agent_id, version_number)
);
