-- The org this user created at signup (password or Google new-user), as opposed to one they later
-- joined by accepting someone else's invitation. Used to gate the admin-emails ADMIN promotion (I1
-- round 2) to the org the listed address actually created, never a stranger's org.
ALTER TABLE app_user ADD COLUMN signup_org_id UUID REFERENCES org(id);

-- Backfill for existing rows: the user's earliest membership, unless that membership came from
-- accepting an invitation addressed to this user's email (in which case there is no signup org to
-- record, and the column stays null).
UPDATE app_user u
SET signup_org_id = (
    SELECT m.org_id FROM org_membership m WHERE m.user_id = u.id ORDER BY m.created_at ASC LIMIT 1
)
WHERE NOT EXISTS (
    SELECT 1 FROM invitation i
    WHERE i.accepted_at IS NOT NULL
      AND lower(i.email) = lower(u.email)
      AND i.org_id = (
          SELECT m.org_id FROM org_membership m WHERE m.user_id = u.id ORDER BY m.created_at ASC LIMIT 1
      )
);
