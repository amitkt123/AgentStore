package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAndPasswordTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    UserRepository userRepository;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String email() {
        return "pw-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void signupSendsAVerificationLinkThatVerifiesTheEmailOnce() {
        String email = email();
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Priya", "Org", email, "s3cret-pw", SignupSide.BUYER), AuthResponse.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "verify-email");

        var first = rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", token), Void.class);
        var second = rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", token), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void signupStillSucceedsWhenTheVerificationEmailFailsToSend() {
        String email = email();
        emails.failNextSendTo(email);

        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Priya", "Org", email, "s3cret-pw", SignupSide.BUYER), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotNull();
        assertThat(userRepository.findByEmailIgnoreCase(email)).isPresent();
    }

    @Test
    void forgotPasswordForAnUnknownEmailLooksTheSameAndSendsNothing() {
        String nobody = email();

        var response = rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", nobody), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(emails.lastTo(nobody)).isEmpty();
    }

    @Test
    void forgotPasswordStillReturnsAcceptedWhenTheResetEmailFailsToSend() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        emails.failNextSendTo(email);

        var response = rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void concurrentResetsWithTheSameLinkOnlyOneSucceeds() throws Exception {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<HttpStatus> attempt = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return HttpStatus.valueOf(rest.postForEntity(url("/api/v1/auth/password/reset"),
                    Map.of("token", token, "password", "new-s3cret-pw"), String.class).getStatusCode().value());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatus> first = pool.submit(attempt);
            Future<HttpStatus> second = pool.submit(attempt);
            List<HttpStatus> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.BAD_REQUEST);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void resetSetsANewPasswordAndEndsRememberedSessions() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String refresh = rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "s3cret-pw", true),
                AuthResponse.class).getBody().refreshToken();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email.toUpperCase()), Void.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");

        var reset = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", token, "password", "new-s3cret-pw"), Void.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "new-s3cret-pw", false),
                AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", refresh), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anOlderResetLinkStopsWorkingWhenANewOneIsRequested() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String older = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);

        var response = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", older, "password", "new-s3cret-pw"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aResetPasswordMustBeAtLeastEightCharacters() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");

        var response = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", token, "password", "short"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
