CREATE TABLE connection (
    id UUID PRIMARY KEY,
    buyer_org_id UUID NOT NULL REFERENCES org(id),
    agent_id UUID NOT NULL REFERENCES agent(id),
    granted_scopes JSONB NOT NULL,
    spend_cap NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
