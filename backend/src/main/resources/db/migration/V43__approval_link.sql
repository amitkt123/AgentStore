CREATE TABLE approval_link (
    id                 UUID PRIMARY KEY,
    notification_id    UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    gate_id            UUID        NOT NULL,
    token_hash         TEXT        NOT NULL UNIQUE,
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ NULL,
    decided_ip         TEXT        NULL,
    decided_user_agent TEXT        NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_link_notification ON approval_link (notification_id);
