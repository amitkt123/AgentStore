CREATE TABLE review_item (
    id UUID PRIMARY KEY,
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    status VARCHAR(20) NOT NULL,
    reviewer_user_id UUID,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);
