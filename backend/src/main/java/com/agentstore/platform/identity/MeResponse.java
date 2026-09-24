package com.agentstore.platform.identity;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID userId, String email, String fullName, boolean emailVerified, UUID activeOrgId,
                         List<Membership> memberships) {

    public record Membership(UUID orgId, String orgName, OrgType orgType, UserRole role, String title) {
        static Membership from(OrgMembership m) {
            return new Membership(m.getOrg().getId(), m.getOrg().getName(), m.getOrg().getType(), m.getRole(),
                    m.getTitle());
        }
    }
}
