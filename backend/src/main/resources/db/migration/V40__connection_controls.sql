ALTER TABLE connection ADD COLUMN monthly_limit NUMERIC(12,2);   -- null = no limit
CREATE TABLE connection_approver (
    connection_id UUID NOT NULL REFERENCES connection(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (connection_id, user_id)
);
