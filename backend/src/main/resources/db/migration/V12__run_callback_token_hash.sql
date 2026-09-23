ALTER TABLE run RENAME COLUMN callback_token TO callback_token_hash;
-- Existing values are plaintext tokens. Replace them with random 64-hex strings nobody knows the preimage of,
-- which invalidates every in-flight token (acceptable pre-launch, spec WS0 B5).
UPDATE run SET callback_token_hash = md5(random()::text) || md5(random()::text);
