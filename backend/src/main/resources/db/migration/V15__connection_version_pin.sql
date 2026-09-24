ALTER TABLE connection ADD COLUMN agent_version_id UUID REFERENCES agent_version(id);
-- Pin existing connections to the version they were effectively running: the agent's latest LIVE version.
UPDATE connection c SET agent_version_id = (
    SELECT v.id FROM agent_version v
    WHERE v.agent_id = c.agent_id AND v.status = 'LIVE'
    ORDER BY v.approved_at DESC NULLS LAST
    LIMIT 1);
