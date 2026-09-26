-- WS5b build pipeline (spec §4.1, §4.2). sha256 is VARCHAR(64), not CHAR(64): Hibernate's schema validation
-- rejects bpchar for a String column.
CREATE TABLE agent_bundle (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    uploaded_by_user_id UUID NOT NULL REFERENCES app_user(id),
    storage_key VARCHAR(512) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE template_build (
    id UUID PRIMARY KEY,
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    bundle_id UUID NOT NULL REFERENCES agent_bundle(id),
    backend VARCHAR(20) NOT NULL,
    runtime VARCHAR(30) NOT NULL,
    entrypoint VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    image_ref VARCHAR(255),
    failure_code VARCHAR(30),
    failure_reason TEXT,
    log_storage_key VARCHAR(512),
    attempts INT NOT NULL DEFAULT 0,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX template_build_status_idx ON template_build (status, queued_at);
CREATE INDEX template_build_version_idx ON template_build (agent_version_id);
CREATE INDEX template_build_bundle_idx ON template_build (bundle_id);

ALTER TABLE agent_version ADD COLUMN bundle_id UUID REFERENCES agent_bundle(id);
ALTER TABLE agent_version ADD COLUMN build_status VARCHAR(20);
ALTER TABLE agent_version ALTER COLUMN image_ref DROP NOT NULL;
