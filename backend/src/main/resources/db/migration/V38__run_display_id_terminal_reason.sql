CREATE SEQUENCE run_no_seq;
CREATE SEQUENCE sandbox_run_no_seq;
CREATE SEQUENCE review_no_seq;

ALTER TABLE run ADD COLUMN display_id VARCHAR(20);
ALTER TABLE run ADD COLUMN terminal_reason VARCHAR(30);
ALTER TABLE run ADD COLUMN reached_block VARCHAR(100);
ALTER TABLE run ADD COLUMN summary TEXT;
ALTER TABLE review_item ADD COLUMN display_id VARCHAR(20);

-- Backfill display ids in creation order, then point each sequence past them.
UPDATE run r SET display_id = 'R-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY started_at NULLS LAST, id) rn FROM run WHERE mode <> 'SANDBOX') o
WHERE r.id = o.id;
UPDATE run r SET display_id = 'S-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY started_at NULLS LAST, id) rn FROM run WHERE mode = 'SANDBOX') o
WHERE r.id = o.id;
UPDATE review_item ri SET display_id = 'RV-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY created_at NULLS LAST, id) rn FROM review_item) o
WHERE ri.id = o.id;

SELECT setval('run_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM run WHERE mode <> 'SANDBOX') x;
SELECT setval('sandbox_run_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM run WHERE mode = 'SANDBOX') x;
SELECT setval('review_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM review_item) x;

-- New rows get their id from the database, so every insert path (and every test) keeps working.
CREATE FUNCTION assign_run_display_id() RETURNS trigger AS $$
BEGIN
    IF NEW.display_id IS NULL THEN
        NEW.display_id := CASE WHEN NEW.mode = 'SANDBOX'
            THEN 'S-' || nextval('sandbox_run_no_seq')
            ELSE 'R-' || nextval('run_no_seq') END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER run_display_id BEFORE INSERT ON run FOR EACH ROW EXECUTE FUNCTION assign_run_display_id();

CREATE FUNCTION assign_review_display_id() RETURNS trigger AS $$
BEGIN
    IF NEW.display_id IS NULL THEN
        NEW.display_id := 'RV-' || nextval('review_no_seq');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER review_display_id BEFORE INSERT ON review_item FOR EACH ROW EXECUTE FUNCTION assign_review_display_id();

ALTER TABLE run ALTER COLUMN display_id SET NOT NULL;
ALTER TABLE run ADD CONSTRAINT run_display_id_key UNIQUE (display_id);
ALTER TABLE review_item ALTER COLUMN display_id SET NOT NULL;
ALTER TABLE review_item ADD CONSTRAINT review_item_display_id_key UNIQUE (display_id);

-- terminal_reason: only where status plus the closing step make the reason unambiguous.
UPDATE run SET terminal_reason = 'FINISHED' WHERE status = 'COMPLETED';
UPDATE run SET terminal_reason = 'TIMED_OUT' WHERE status = 'TIMED_OUT';
UPDATE run SET terminal_reason = 'GATE_EXPIRED' WHERE status = 'EXPIRED';   -- EXPIRED only ever comes from gate expiry
UPDATE run r SET terminal_reason = CASE
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'CANCELLED') THEN 'CANCELLED'
        ELSE 'GATE_REJECTED' END
WHERE r.status = 'CANCELLED';
UPDATE run r SET terminal_reason = CASE
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'COST_CAP_REACHED') THEN 'COST_CAP_REACHED'
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'FAILED' AND s.detail->>'code' = 'SCOPE_VIOLATIONS') THEN 'SCOPE_VIOLATIONS'
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'FAILED'
                     AND s.detail->>'summary' = 'Agent stopped without reporting completion') THEN 'SANDBOX_LOST'
        ELSE 'AGENT_FAILED' END
WHERE r.status = 'FAILED';

UPDATE run r SET summary = (SELECT s.detail->>'summary' FROM run_step s
    WHERE s.run_id = r.id AND s.kind IN ('COMPLETE', 'FAILED', 'TIMEOUT', 'COST_CAP_REACHED', 'CANCELLED')
    ORDER BY s.created_at DESC LIMIT 1)
WHERE r.status IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'EXPIRED', 'CANCELLED');
UPDATE run r SET reached_block = (SELECT s.block FROM run_step s
    WHERE s.run_id = r.id AND s.block IS NOT NULL ORDER BY s.created_at DESC LIMIT 1);

CREATE INDEX run_reason_idx ON run (terminal_reason);
