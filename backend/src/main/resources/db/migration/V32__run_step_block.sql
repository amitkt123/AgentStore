ALTER TABLE run_step ADD COLUMN block VARCHAR(40);
-- 'read:<resource>' | 'llm' | 'draft:<resource>' | 'gate:<action>' | 'write:<resource>' | 'charge' | NULL
