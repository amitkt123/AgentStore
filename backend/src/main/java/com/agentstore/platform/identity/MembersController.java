package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}")
public class MembersController {

    private final OrgMembershipRepository membershipRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final InvitationService invitationService;

    public MembersController(OrgMembershipRepository membershipRepository, OrgRepository orgRepository,
                             UserRepository userRepository, InvitationService invitationService) {
        this.membershipRepository = membershipRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.invitationService = invitationService;
    }

    public record Member(UUID userId, String fullName, String email, UserRole role, String title) {
        static Member from(OrgMembership m) {
            return new Member(m.getUser().getId(), m.getUser().getFullName(), m.getUser().getEmail(), m.getRole(),
                    m.getTitle());
        }
    }

    public record InviteRequest(@Email @NotBlank String email, @NotNull UserRole role, @Size(max = 100) String title) {}

    public record UpdateMemberRequest(UserRole role, @Size(max = 100) String title) {}

    @GetMapping("/members")
    @PreAuthorize("isAuthenticated()")
    public List<Member> members(@PathVariable UUID orgId) {
        requireSameOrg(orgId);
        return membershipRepository.findByIdOrgIdOrderByCreatedAtAsc(orgId).stream().map(Member::from).toList();
    }

    @PatchMapping("/members/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Member update(@PathVariable UUID orgId, @PathVariable UUID userId,
                         @Valid @RequestBody UpdateMemberRequest request) {
        requireManager(orgId);
        orgRepository.findByIdForUpdate(orgId);   // lock the org row before reading any membership's role
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(userId, orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (request.role() != null && request.role() != membership.getRole()) {
            if (!OrgRoles.assignable(membership.getOrg().getType()).contains(request.role())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role not available here");
            }
            requireAnotherAdminRemains(membership);
            membership.setRole(request.role());
        }
        if (request.title() != null) {
            membership.setTitle(request.title().isBlank() ? null : request.title().trim());
        }
        return Member.from(membershipRepository.save(membership));
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
        requireManager(orgId);
        orgRepository.findByIdForUpdate(orgId);   // lock the org row before reading any membership's role
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(userId, orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        requireAnotherAdminRemains(membership);
        membershipRepository.delete(membership);
        User user = membership.getUser();
        if (orgId.equals(user.getLastOrgId())) {
            user.setLastOrgId(null);
            userRepository.save(user);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvitationService.View> invite(@PathVariable UUID orgId,
                                                         @Valid @RequestBody InviteRequest request) {
        var claims = requireManager(orgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                invitationService.invite(orgId, claims.userId(), request.email(), request.role(), request.title()));
    }

    @GetMapping("/invitations")
    @PreAuthorize("isAuthenticated()")
    public List<InvitationService.View> pendingInvitations(@PathVariable UUID orgId) {
        requireManager(orgId);
        return invitationService.pending(orgId);
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        requireManager(orgId);
        invitationService.revoke(orgId, invitationId);
        return ResponseEntity.noContent().build();
    }

    private JwtService.Claims requireSameOrg(UUID orgId) {
        var claims = MeController.currentClaims();
        if (!claims.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workspace");
        }
        return claims;
    }

    /**
     * Requires the caller to hold a manager role in this org RIGHT NOW, per the database, not per the JWT.
     * A JWT's role is a snapshot from login time; if the caller was demoted or removed since, their token
     * can be up to {@code agentstore.jwt.expiration-minutes} stale, and trusting it would let a demoted
     * admin keep managing members (including re-promoting themselves) until it expires.
     */
    private JwtService.Claims requireManager(UUID orgId) {
        var claims = requireSameOrg(orgId);
        OrgMembership current = membershipRepository.findById(new OrgMembershipId(claims.userId(), orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only workspace admins can manage members"));
        if (!OrgRoles.canManageMembers(current.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace admins can manage members");
        }
        return claims;
    }

    /**
     * Refuses to leave an org without anyone holding its admin role. Callers MUST lock the org row via
     * {@link OrgRepository#findByIdForUpdate} and only load {@code membership} afterwards, in the same
     * transaction, before calling this: an entity loaded before the lock keeps whatever role it had at load
     * time even after the lock is acquired (JPA's persistence-context cache does not re-fetch it), so reading
     * a pre-lock snapshot here could see a membership as non-admin when a concurrent request has since
     * promoted it, letting two admin-count checks each pass on a workspace that ends up with zero admins.
     */
    private void requireAnotherAdminRemains(OrgMembership membership) {
        UserRole adminRole = OrgRoles.adminRoleOf(membership.getOrg().getType());
        if (membership.getRole() == adminRole
                && membershipRepository.countByIdOrgIdAndRole(membership.getId().getOrgId(), adminRole) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A workspace needs at least one admin");
        }
    }
}
