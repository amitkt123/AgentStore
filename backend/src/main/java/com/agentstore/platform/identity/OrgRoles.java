package com.agentstore.platform.identity;

import java.util.EnumSet;
import java.util.Set;

/** Which roles exist in which kind of org, and who may manage members. ADMIN is never assignable here. */
public final class OrgRoles {

    private OrgRoles() {
    }

    public static Set<UserRole> assignable(OrgType type) {
        return type == OrgType.BUYER
                ? EnumSet.of(UserRole.BUYER_ADMIN, UserRole.BUYER_APPROVER)
                : EnumSet.of(UserRole.DEVELOPER);
    }

    public static boolean canManageMembers(UserRole role) {
        return role == UserRole.BUYER_ADMIN || role == UserRole.DEVELOPER;
    }

    /** The role that must always have at least one holder in an org of this type. */
    public static UserRole adminRoleOf(OrgType type) {
        return type == OrgType.BUYER ? UserRole.BUYER_ADMIN : UserRole.DEVELOPER;
    }
}
