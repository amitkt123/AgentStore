CREATE TABLE run (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES connection(id),
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    status VARCHAR(20) NOT NULL,
    cost_cap NUMERIC(12,2) NOT NULL,
    total_charged NUMERIC(12,2) NOT NULL DEFAULT 0,
    sandbox_id VARCHAR(255),
    callback_token VARCHAR(255) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
