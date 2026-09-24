CREATE TABLE user_identity (
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    user_id    UUID         NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);
CREATE INDEX user_identity_user_idx ON user_identity (user_id);
