ALTER TABLE gate_request ADD COLUMN estimated_units NUMERIC(14,4);
ALTER TABLE gate_request ADD COLUMN preview JSONB;
ALTER TABLE gate_request ADD COLUMN decided_via VARCHAR(20);
ALTER TABLE gate_request ADD COLUMN eligible_approver_ids JSONB;   -- null = unrestricted
UPDATE gate_request SET decided_via = 'WEB' WHERE status IN ('APPROVED', 'REJECTED');
