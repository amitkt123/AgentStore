CREATE TABLE secret (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES connection(id),
    name VARCHAR(100) NOT NULL,
    encrypted_value BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connection_id, name)
);
