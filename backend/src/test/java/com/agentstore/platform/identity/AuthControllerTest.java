package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    OrgMembershipRepository membershipRepository;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String unique(String local) {
        return local + "-" + UUID.randomUUID() + "@acme.example";
    }

    JwtService.Claims claimsOf(AuthResponse response) {
        return jwtService.parse(response.token()).orElseThrow();
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void buyerSignupCreatesAWorkspaceAndMakesTheUserItsAdmin() {
        String email = unique("priya");
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Priya Shah", "Meridian Traders", email, "s3cret-pw", SignupSide.BUYER),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var claims = claimsOf(response.getBody());
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_ADMIN);
        User user = userRepository.findById(claims.userId()).orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Priya Shah");
        assertThat(user.getLastOrgId()).isEqualTo(claims.orgId());
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getOrg().getType()).isEqualTo(OrgType.BUYER);
                    assertThat(m.getOrg().getName()).isEqualTo("Meridian Traders");
                });
    }

    @Test
    void developerSignupGivesTheDeveloperRole() {
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Asha Kulkarni", "Ledgerline Labs", unique("asha"), "s3cret-pw", SignupSide.DEVELOPER),
                AuthResponse.class);

        assertThat(claimsOf(response.getBody()).role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void listedAdminEmailIsPromotedToAdminOnlyByACompletedPasswordResetNotByVerifyingAlone() {
        // application-test.yml lists admin-auth-test@platform.example (EndToEndTierBTest uses admin@platform.example;
        // the test database is shared across classes, so each test class needs its own address)
        String email = "Admin-Auth-Test@Platform.example";
        var signup = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Karya Admin", "Karya", email, "s3cret-pw", SignupSide.DEVELOPER),
                AuthResponse.class);
        assertThat(claimsOf(signup.getBody()).role()).isEqualTo(UserRole.DEVELOPER);

        // Verifying the email alone (I1 round 2) never promotes: an impostor could have signed up with
        // this address first, and confirming an unsolicited link doesn't prove control of the password.
        String verifyToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "verify-email");
        rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", verifyToken), Void.class);
        var loginAfterVerify = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false), AuthResponse.class);
        assertThat(claimsOf(loginAfterVerify.getBody()).role()).isEqualTo(UserRole.DEVELOPER);

        // A completed password reset proves control of the inbox and replaces the password, so it does.
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String resetToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", resetToken, "password", "new-s3cret-pw"), Void.class);
        var loginAfterReset = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "new-s3cret-pw", false), AuthResponse.class);
        assertThat(claimsOf(loginAfterReset.getBody()).role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void anUnlistedEmailStaysTheOrdinaryRoleAfterVerifyingAndResetting() {
        String email = unique("notadmin");
        var signup = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Not Admin", "Karya", email, "s3cret-pw", SignupSide.DEVELOPER),
                AuthResponse.class);
        assertThat(claimsOf(signup.getBody()).role()).isEqualTo(UserRole.DEVELOPER);

        String verifyToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "verify-email");
        rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", verifyToken), Void.class);

        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String resetToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", resetToken, "password", "new-s3cret-pw"), Void.class);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "new-s3cret-pw", false), AuthResponse.class);
        assertThat(claimsOf(login.getBody()).role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void aListedEmailWhoseAccountCameFromAcceptingAnInvitationIsNeverPromotedByAReset() {
        // admin-invited-test@platform.example is listed only for this test: the user is never created by
        // IdentityService.signup/addOrg, so it has no signup org of its own (I1 round 2) — it joins a
        // stranger's org through a real invitation instead.
        String email = "admin-invited-test@platform.example";
        OrgMembership inviter = identity.buyerAdmin("inviter-" + UUID.randomUUID() + "@acme.example");
        var invite = rest.exchange(url("/api/v1/orgs/" + inviter.getOrg().getId() + "/invitations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "role", "BUYER_MEMBER"), bearer(identity.tokenFor(inviter))),
                String.class);
        assertThat(invite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String inviteToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "invite");
        var accepted = rest.postForEntity(url("/api/v1/invitations/" + inviteToken + "/accept"),
                Map.of("fullName", "Invited Admin", "password", "s3cret-pw"), AuthResponse.class);
        assertThat(claimsOf(accepted.getBody()).role()).isEqualTo(UserRole.BUYER_MEMBER);

        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String resetToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", resetToken, "password", "new-s3cret-pw"), Void.class);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "new-s3cret-pw", false), AuthResponse.class);
        assertThat(claimsOf(login.getBody()).role()).isEqualTo(UserRole.BUYER_MEMBER);
    }

    @Test
    void signupEmailIsCaseInsensitiveAndUnique() {
        String email = unique("Case.Test");
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org A", email, "s3cret-pw", SignupSide.BUYER), AuthResponse.class);

        var duplicate = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("B", "Org B", email.toUpperCase(), "s3cret-pw", SignupSide.BUYER), String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email.toLowerCase(), "s3cret-pw", false), AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        String email = unique("wrongpw");
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org", email, "correct-pw", SignupSide.BUYER), AuthResponse.class);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "incorrect-pw", false), AuthResponse.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signupRequiresASide() {
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org", unique("noside"), "s3cret-pw", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void removeFromOrg(OrgMembership membership) {
        var response = rest.exchange(
                url("/api/v1/orgs/" + membership.getOrg().getId() + "/members/" + membership.getUser().getId()),
                HttpMethod.DELETE, new HttpEntity<>(bearer(identity.tokenFor(membership))), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void loginWithNoMembershipAndNoWorkspaceFieldsIsRejectedWithNoWorkspace() {
        String email = unique("orphan");
        OrgMembership admin = identity.buyerAdmin(email);
        // The last admin can't remove themself, so bring in a second admin to remove the first.
        OrgMembership second = identity.member(admin.getOrg(), unique("second"), UserRole.BUYER_ADMIN, null);
        removeFromOrg(admin);

        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("NO_WORKSPACE");
    }

    @Test
    void loginWithNoMembershipAndWorkspaceFieldsCreatesANewWorkspaceWithTheSidesAdminRoleNotPlatformAdmin() {
        String email = unique("rejoins");
        OrgMembership admin = identity.buyerAdmin(email);
        OrgMembership second = identity.member(admin.getOrg(), unique("second"), UserRole.BUYER_ADMIN, null);
        removeFromOrg(admin);

        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false, SignupSide.DEVELOPER, "Fresh Start Co"),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var claims = claimsOf(response.getBody());
        assertThat(claims.role()).isEqualTo(UserRole.DEVELOPER);
        User user = userRepository.findById(admin.getUser().getId()).orElseThrow();
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getOrg().getName()).isEqualTo("Fresh Start Co");
                    assertThat(m.getOrg().getType()).isEqualTo(OrgType.DEVELOPER);
                });
    }

    @Test
    void wrongPasswordWithNoMembershipIsStillUnauthorizedNotNoWorkspace() {
        String email = unique("wrongpwnomember");
        OrgMembership admin = identity.buyerAdmin(email);
        OrgMembership second = identity.member(admin.getOrg(), unique("second"), UserRole.BUYER_ADMIN, null);
        removeFromOrg(admin);

        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "not-the-password", false, SignupSide.DEVELOPER, "Fresh Start Co"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithAnExistingMembershipIgnoresWorkspaceFieldsAndDoesNotCreateAnOrg() {
        String email = unique("hasmembership");
        OrgMembership admin = identity.buyerAdmin(email);

        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false, SignupSide.DEVELOPER, "Should Not Exist"),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimsOf(response.getBody()).orgId()).isEqualTo(admin.getOrg().getId());
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(admin.getUser().getId())).hasSize(1);
    }

    @Test
    void aListedAdminEmailRecreatingAWorkspaceThisWayDoesNotBecomeAdmin() {
        // Listed in application-test.yml for this test class only.
        String email = "admin-lockout-test@platform.example";
        OrgMembership admin = identity.buyerAdmin(email);
        User user = admin.getUser();
        user.setEmailVerifiedAt(java.time.Instant.now());
        userRepository.save(user);
        OrgMembership second = identity.member(admin.getOrg(), unique("second"), UserRole.BUYER_ADMIN, null);
        removeFromOrg(admin);

        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false, SignupSide.DEVELOPER, "Reclaimed Co"),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimsOf(response.getBody()).role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void loginWithAnOverlongOrgNameIsRejectedWith400NotAServerError() {
        String email = unique("overlongorg");
        OrgMembership admin = identity.buyerAdmin(email);
        OrgMembership second = identity.member(admin.getOrg(), unique("second"), UserRole.BUYER_ADMIN, null);
        removeFromOrg(admin);

        String tooLong = "x".repeat(256);
        var response = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false, SignupSide.DEVELOPER, tooLong), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aUserWhoseOldSignupOrgMembershipWasRemovedKeepsItAndIsNotPromotedThroughTheRecreatedWorkspace() {
        // Listed in application-test.yml for this test class only.
        String email = "admin-lockout-signuporg-test@platform.example";
        var signup = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Karya Admin", "Old Co", email, "s3cret-pw", SignupSide.DEVELOPER),
                AuthResponse.class);
        assertThat(claimsOf(signup.getBody()).role()).isEqualTo(UserRole.DEVELOPER);

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        UUID oldSignupOrgId = user.getSignupOrgId();
        assertThat(oldSignupOrgId).isNotNull();

        OrgMembership original = membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId())
                .stream().findFirst().orElseThrow();
        // A second developer joins the old org so the original member isn't its last, and can be removed.
        identity.member(original.getOrg(), unique("second-dev"), UserRole.DEVELOPER, null);
        removeFromOrg(original);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "s3cret-pw", false, SignupSide.DEVELOPER, "New Co"), AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimsOf(login.getBody()).role()).isEqualTo(UserRole.DEVELOPER);

        // The recreated workspace must not overwrite signupOrgId: the user's old (now member-less) org
        // is still what promoteToAdminIfListed would look at, exactly like /me/orgs.
        User afterLogin = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterLogin.getSignupOrgId()).isEqualTo(oldSignupOrgId);

        // A completed password reset only ever promotes the signup-org membership, which no longer
        // exists, so the recreated workspace's role stays the side's ordinary role, never ADMIN.
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String resetToken = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", resetToken, "password", "new-s3cret-pw"), Void.class);

        var loginAfterReset = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "new-s3cret-pw", false), AuthResponse.class);
        assertThat(loginAfterReset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimsOf(loginAfterReset.getBody()).role()).isEqualTo(UserRole.DEVELOPER);
    }
}
