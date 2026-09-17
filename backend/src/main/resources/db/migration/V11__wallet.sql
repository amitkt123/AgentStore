CREATE TABLE wallet (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL UNIQUE,
    balance NUMERIC(14,2) NOT NULL,
    cap NUMERIC(14,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
