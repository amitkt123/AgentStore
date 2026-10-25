-- BUYER_APPROVER becomes BUYER_MEMBER: buyer admins can now decide gates too, so the role no longer names what sets it apart.
UPDATE org_membership SET role = 'BUYER_MEMBER' WHERE role = 'BUYER_APPROVER';
UPDATE invitation SET role = 'BUYER_MEMBER' WHERE role = 'BUYER_APPROVER';
UPDATE app_user SET role = 'BUYER_MEMBER' WHERE role = 'BUYER_APPROVER';
