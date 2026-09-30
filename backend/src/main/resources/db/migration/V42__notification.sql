CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    category        TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    run_id          UUID        NULL,
    gate_id         UUID        NULL,
    source_event_id UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ NULL,
    resolved_at     TIMESTAMPTZ NULL,
    email_due_at    TIMESTAMPTZ NULL,
    email_sent_at   TIMESTAMPTZ NULL,
    email_attempts  INT         NOT NULL DEFAULT 0,
    email_error     TEXT        NULL,
    email_skipped   BOOLEAN     NOT NULL DEFAULT false,
    email_no_link   BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT uq_notification_user_event UNIQUE (user_id, source_event_id)
);
CREATE INDEX idx_notification_user ON notification (user_id, org_id, created_at DESC);
CREATE INDEX idx_notification_gate ON notification (gate_id) WHERE gate_id IS NOT NULL;
CREATE INDEX idx_notification_email_due ON notification (email_due_at)
    WHERE email_sent_at IS NULL AND NOT email_skipped;

CREATE TABLE notification_preference (
    user_id  UUID    NOT NULL,
    category TEXT    NOT NULL,
    web      BOOLEAN NOT NULL,
    email    BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, category)
);

ALTER TABLE app_user
    ADD COLUMN quiet_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN quiet_start   TIME    NOT NULL DEFAULT '20:00',
    ADD COLUMN quiet_end     TIME    NOT NULL DEFAULT '08:00',
    ADD COLUMN quiet_tz      TEXT    NOT NULL DEFAULT 'Asia/Kolkata';

ALTER TABLE run ADD COLUMN started_by_user_id UUID NULL;
