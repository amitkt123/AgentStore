package com.agentstore.platform.execution;

import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may decide a gate, snapshotted when it opens. Today that is the buyer org's BUYER_APPROVER members, which is the
 * rule the decision endpoints already enforce through their role check, made explicit. WS15 swaps the source for the
 * configurable approver list. A sandbox run has no buyer org, and an org with no approver members would otherwise
 * deadlock, so both snapshot as null (unrestricted).
 */
@Component
public class EligibleApprovers {

    private final OrgMembershipRepository memberships;
    private final ObjectMapper mapper;

    public EligibleApprovers(OrgMembershipRepository memberships, ObjectMapper mapper) {
        this.memberships = memberships;
        this.mapper = mapper;
    }

    /** JSON array of user ids, or null for "unrestricted". */
    public String snapshotJson(Run run) {
        if (run.getConnection() == null) {
            return null;
        }
        List<UUID> ids = memberships.findByIdOrgIdOrderByCreatedAtAsc(run.getConnection().getBuyerOrg().getId()).stream()
                .filter(m -> m.getRole() == UserRole.BUYER_APPROVER)
                .map(m -> m.getUser().getId())
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
