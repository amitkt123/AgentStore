package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperApiKeyControllerTest extends AbstractIntegrationTest {

    static final String PATH = "/api/v1/developer/api-keys";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired DeveloperApiKeyRepository keys;
    @Autowired JwtService jwtService;

    record Dev(Org org, String token) {}

    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        return new Dev(org, identity.tokenFor(m));
    }

    <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> create(Dev dev, String name) {
        var response = call(HttpMethod.POST, PATH, dev.token(), Map.of("name", name), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void createShowsTheKeyOnceAndStoresOnlyItsHash() {
        Dev dev = developer();
        Map<String, Object> created = create(dev, "laptop");

        String key = (String) created.get("key");
        assertThat(key).startsWith("ask_live_").hasSize(52);
        assertThat(created.get("prefix")).isEqualTo(key.substring(0, 12));

        DeveloperApiKey row = keys.findByKeyHash(ApiKeys.hash(key)).orElseThrow();
        assertThat(row.getOrgId()).isEqualTo(dev.org().getId());
        assertThat(row.getKeyHash()).hasSize(64).isNotEqualTo(key);

        var listed = call(HttpMethod.GET, PATH, dev.token(), null, List.class);
        assertThat(listed.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) listed.getBody().get(0);
        assertThat(item.containsKey("key")).isFalse();
        assertThat(item.containsKey("keyHash")).isFalse();
        assertThat(item.get("name")).isEqualTo("laptop");
        assertThat(item.get("revokedAt")).isNull();
        assertThat(listed.getBody().toString()).doesNotContain(key);
    }

    @Test
    void listShowsOnlyTheCallersOrg() {
        Dev mine = developer();
        Dev theirs = developer();
        create(mine, "mine");
        create(theirs, "theirs");

        var listed = call(HttpMethod.GET, PATH, mine.token(), null, List.class);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) listed.getBody().get(0)).get("name")).isEqualTo("mine");
    }

    @Test
    void revokeMarksTheKeyAndIsIdempotent() {
        Dev dev = developer();
        String id = (String) create(dev, "ci").get("id");

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, dev.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        var firstRevokedAt = keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt();
        assertThat(firstRevokedAt).isNotNull();

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, dev.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    void revokingAnotherOrgsKeyIsNotFound() {
        Dev owner = developer();
        Dev intruder = developer();
        String id = (String) create(owner, "ci").get("id");

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, intruder.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void aBlankNameIsRejected() {
        Dev dev = developer();
        assertThat(call(HttpMethod.POST, PATH, dev.token(), Map.of("name", "  "), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNameOverOneHundredCharactersIsRejected() {
        Dev dev = developer();
        assertThat(call(HttpMethod.POST, PATH, dev.token(), Map.of("name", "x".repeat(101)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyDevelopersCanManageKeys() {
        Dev dev = developer();
        String buyer = identity.tokenFor(identity.buyerAdmin("buyer-" + UUID.randomUUID() + "@example.com"));
        String admin = jwtService.issue(UUID.randomUUID(), dev.org().getId(), UserRole.ADMIN);

        assertThat(call(HttpMethod.POST, PATH, buyer, Map.of("name", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, PATH, admin, Map.of("name", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, PATH, null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
