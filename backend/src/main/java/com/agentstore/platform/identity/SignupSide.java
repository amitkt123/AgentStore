package com.agentstore.platform.identity;

/** "I run finance" or "I build agents" on the sign-up screen. */
public enum SignupSide {
    BUYER, DEVELOPER;

    public OrgType orgType() {
        return this == BUYER ? OrgType.BUYER : OrgType.DEVELOPER;
    }
}
