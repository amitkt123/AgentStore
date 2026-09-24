ALTER TABLE app_user ADD COLUMN full_name VARCHAR(255);
ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN last_org_id UUID REFERENCES org(id);
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;   -- Google-only users (Task 6)

-- Assumes no two existing emails differ only by case (accepted pre-launch).
UPDATE app_user SET email = lower(trim(email));
CREATE UNIQUE INDEX app_user_email_lower_idx ON app_user (lower(email));

CREATE TABLE org_membership (
    user_id    UUID         NOT NULL REFERENCES app_user(id),
    org_id     UUID         NOT NULL REFERENCES org(id),
    role       VARCHAR(30)  NOT NULL,
    title      VARCHAR(100),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, org_id)
);
CREATE INDEX org_membership_org_idx ON org_membership (org_id);

INSERT INTO org_membership (user_id, org_id, role, created_at)
SELECT id, org_id, role, created_at FROM app_user;
UPDATE app_user SET last_org_id = org_id;

-- Expand phase: the legacy columns stay, nullable, so the previous build can still boot during a rollback.
-- A later contract migration drops them once nothing reads them.
ALTER TABLE app_user ALTER COLUMN org_id DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN role DROP NOT NULL;
