CREATE TABLE invitation (
    id                 UUID         PRIMARY KEY,
    org_id             UUID         NOT NULL REFERENCES org(id),
    email              VARCHAR(255) NOT NULL,
    role               VARCHAR(30)  NOT NULL,
    title              VARCHAR(100),
    token_hash         CHAR(64)     NOT NULL UNIQUE,
    invited_by_user_id UUID         REFERENCES app_user(id),
    expires_at         TIMESTAMPTZ  NOT NULL,
    accepted_at        TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX invitation_org_idx ON invitation (org_id);
