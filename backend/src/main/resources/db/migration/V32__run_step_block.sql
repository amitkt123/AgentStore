ALTER TABLE run_step ADD COLUMN block TEXT;
-- 'read:<resource>' | 'llm' | 'draft:<resource>' | 'gate:<action>' | 'write:<resource>' | 'charge' | NULL
-- TEXT, not a bounded VARCHAR: manifest resource/gate names have no length cap (scope/gate schema fields go up to
-- 100 chars) and an undeclared SCOPE_VIOLATION's target is attacker-controlled, so a bounded column truncates or
-- 500s on a legitimate long name and silently drops the audit trail for an out-of-scope call.
