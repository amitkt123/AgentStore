package com.agentstore.platform.identity;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InvitationService {

    static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public InvitationService(InvitationRepository invitationRepository, OrgRepository orgRepository,
                             UserRepository userRepository, OrgMembershipRepository membershipRepository,
                             IdentityService identityService, EmailSender emailSender,
                             @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.invitationRepository = invitationRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl;
    }

    public record View(UUID id, String email, UserRole role, String title, Instant expiresAt) {
        static View from(Invitation i) {
            return new View(i.getId(), i.getEmail(), i.getRole(), i.getTitle(), i.getExpiresAt());
        }
    }

    public record Preview(String orgName, OrgType orgType, String email, UserRole role, String invitedBy) {}

    @Transactional
    public View invite(UUID orgId, UUID inviterUserId, String rawEmail, UserRole role, String title) {
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        if (!OrgRoles.assignable(org.getType()).contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role " + role + " is not available here");
        }
        String email = IdentityService.normalizeEmail(rawEmail);
        boolean alreadyMember = userRepository.findByEmailIgnoreCase(email)
                .flatMap(u -> membershipRepository.findById(new OrgMembershipId(u.getId(), orgId)))
                .isPresent();
        if (alreadyMember) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a member of this workspace");
        }
        User inviter = userRepository.findById(inviterUserId).orElse(null);
        String rawToken = TokenHasher.newToken();

        Invitation invitation = new Invitation();
        invitation.setOrg(org);
        invitation.setEmail(email);
        invitation.setRole(role);
        invitation.setTitle(title);
        invitation.setTokenHash(TokenHasher.sha256Hex(rawToken));
        invitation.setInvitedBy(inviter);
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitation = invitationRepository.save(invitation);

        String inviterName = inviter != null && inviter.getFullName() != null ? inviter.getFullName() : "A teammate";
        emailSender.send(new EmailMessage(email, inviterName + " invited you to " + org.getName() + " on Karya",
                inviterName + " invited you to join " + org.getName() + " on Karya.\n\n"
                        + "Accept the invitation: " + webBaseUrl + "/invite/" + rawToken + "\n\n"
                        + "The link works once and expires in 7 days."));
        return View.from(invitation);
    }

    public List<View> pending(UUID orgId) {
        Instant now = Instant.now();
        return invitationRepository.findByOrgIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(orgId).stream()
                .filter(i -> i.getExpiresAt().isAfter(now))
                .map(View::from).toList();
    }

    @Transactional
    public void revoke(UUID orgId, UUID invitationId) {
        Invitation invitation = invitationRepository.findById(invitationId)
                .filter(i -> i.getOrg().getId().equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (invitation.getRevokedAt() == null && invitation.getAcceptedAt() == null) {
            invitation.setRevokedAt(Instant.now());
            invitationRepository.save(invitation);
        }
    }

    public Preview preview(String rawToken) {
        Invitation i = usable(rawToken);
        String inviter = i.getInvitedBy() != null ? i.getInvitedBy().getFullName() : null;
        return new Preview(i.getOrg().getName(), i.getOrg().getType(), i.getEmail(), i.getRole(), inviter);
    }

    /**
     * Accepts an invitation. {@code signedInUserId} is null for an anonymous caller. A brand-new person needs a
     * name and password; someone who already has an account must be signed in as that account.
     */
    @Transactional
    public OrgMembership accept(String rawToken, UUID signedInUserId, String fullName, String password) {
        Invitation invitation = usable(rawToken);
        User user = userRepository.findByEmailIgnoreCase(invitation.getEmail()).orElse(null);

        if (signedInUserId != null) {
            if (user == null || !user.getId().equals(signedInUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This invitation was sent to a different email address");
            }
        } else if (user != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email exists. Sign in, then accept the invitation.");
        } else {
            if (fullName == null || fullName.isBlank() || password == null || password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name and password are required");
            }
            user = new User(null, invitation.getEmail(), identityService.hashPassword(password), fullName.trim());
            user.setEmailVerifiedAt(Instant.now());   // the link reached this inbox
            user = userRepository.save(user);
        }

        final User member = user;
        OrgMembership membership = membershipRepository
                .findById(new OrgMembershipId(member.getId(), invitation.getOrg().getId()))
                .orElseGet(() -> membershipRepository.save(new OrgMembership(member, invitation.getOrg(),
                        invitation.getRole(), invitation.getTitle())));
        invitation.setAcceptedAt(Instant.now());
        invitationRepository.save(invitation);
        user.setLastOrgId(invitation.getOrg().getId());
        userRepository.save(user);
        return membership;
    }

    private Invitation usable(String rawToken) {
        return invitationRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(i -> i.isUsable(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE,
                        "This invitation has expired or was already used"));
    }
}
