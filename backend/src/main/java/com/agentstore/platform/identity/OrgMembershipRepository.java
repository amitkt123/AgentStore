package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, OrgMembershipId> {
    List<OrgMembership> findByIdUserIdOrderByCreatedAtAsc(UUID userId);

    List<OrgMembership> findByIdOrgIdOrderByCreatedAtAsc(UUID orgId);

    long countByIdOrgIdAndRole(UUID orgId, UserRole role);
}
