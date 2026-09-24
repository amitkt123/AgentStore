package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

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

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String unique(String local) {
        return local + "-" + UUID.randomUUID() + "@acme.example";
    }

    JwtService.Claims claimsOf(AuthResponse response) {
        return jwtService.parse(response.token()).orElseThrow();
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
    void listedAdminEmailsBecomePlatformAdmins() {
        // application-test.yml lists admin-auth-test@platform.example (EndToEndTierBTest uses admin@platform.example;
        // the test database is shared across classes, so each test class needs its own address)
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Karya Admin", "Karya", "Admin-Auth-Test@Platform.example", "s3cret-pw",
                        SignupSide.DEVELOPER),
                AuthResponse.class);

        assertThat(claimsOf(response.getBody()).role()).isEqualTo(UserRole.ADMIN);
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
}
