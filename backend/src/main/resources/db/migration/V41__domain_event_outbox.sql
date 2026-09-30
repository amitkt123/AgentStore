CREATE TABLE domain_event (
    id           UUID PRIMARY KEY,
    type         TEXT        NOT NULL,
    org_id       UUID        NULL,               -- null for events with no buyer org (sandbox runs)
    aggregate_id UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_domain_event_aggregate ON domain_event (aggregate_id);

CREATE TABLE domain_event_delivery (
    id              UUID PRIMARY KEY,
    event_id        UUID        NOT NULL REFERENCES domain_event (id),
    consumer        TEXT        NOT NULL,
    status          TEXT        NOT NULL,        -- PENDING, DONE, DEAD
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT        NULL,
    CONSTRAINT uq_delivery_event_consumer UNIQUE (event_id, consumer)
);
CREATE INDEX idx_delivery_due ON domain_event_delivery (status, next_attempt_at);
