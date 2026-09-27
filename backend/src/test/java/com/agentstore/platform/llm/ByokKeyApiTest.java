package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ByokKeyApiTest extends AbstractIntegrationTest {

    static final String PATH = "/api/v1/developer/llm-keys";
    static final String KEY = "sk-ant-api03-abcdefghijklmnop1234";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired LlmFixtures fx;
    @Autowired ByokKeyService keys;
    @Autowired JdbcTemplate jdbc;

    record Dev(Org org, String token) {}

    Dev developer(boolean verified) {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        if (verified) {
            fx.verifyDeveloperOrg(org);
        }
        OrgMembership membership = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        return new Dev(org, identity.tokenFor(membership));
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<List> list(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + PATH, HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    @Test
    void anUnverifiedOrgCannotStoreAKey() {
        Dev dev = developer(false);

        var response = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("BYOK_NOT_VERIFIED");
        assertThat(keys.list(dev.org().getId())).isEmpty();
        assertThat(response.getBody().toString()).doesNotContain(KEY);
    }

    @Test
    void aVerifiedDeveloperStoresAKeyEncryptedAtRestAndNeverGetsItBack() {
        Dev dev = developer(true);

        var put = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("provider")).isEqualTo("anthropic");
        assertThat(put.getBody().get("last4")).isEqualTo("1234");
        assertThat(put.getBody().get("createdAt")).isNotNull();
        assertThat(put.getBody().toString()).doesNotContain(KEY);

        byte[] stored = jdbc.queryForObject("SELECT encrypted_key FROM byok_credential WHERE developer_org_id = ?",
                byte[].class, dev.org().getId());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(KEY).doesNotContain("abcdefghijklmnop");
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).contains(KEY);

        var listed = list(dev.token());
        assertThat(listed.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) listed.getBody().get(0);
        assertThat(new java.util.HashSet<Object>(item.keySet())).containsExactlyInAnyOrder("provider", "last4", "createdAt");
        assertThat(listed.getBody().toString()).doesNotContain(KEY);
    }

    @Test
    void storingAgainReplacesTheKey() {
        Dev dev = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        var second = call(HttpMethod.PUT, PATH + "/Anthropic", dev.token(), Map.of("apiKey", "sk-ant-second-key-9999"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("last4")).isEqualTo("9999");
        assertThat(keys.list(dev.org().getId())).hasSize(1);
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).contains("sk-ant-second-key-9999");
    }

    @Test
    void aTinyKeyIsNeverFullyEchoedAsLast4() {
        Dev dev = developer(true);

        var put = call(HttpMethod.PUT, PATH + "/openai", dev.token(), Map.of("apiKey", "abc"));

        assertThat(put.getBody().get("last4")).isEqualTo("****");
    }

    @Test
    void deletingRemovesTheKeyAndIsIdempotent() {
        Dev dev = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(call(HttpMethod.DELETE, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(list(dev.token()).getBody()).isEmpty();
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).isEmpty();
    }

    @Test
    void anUnverifiedOrgCanStillDeleteItsKeys() {
        Dev dev = developer(true);
        keys.put(dev.org().getId(), LlmVendor.OPENAI, KEY);
        jdbc.update("UPDATE org SET developer_verified_at = NULL WHERE id = ?", dev.org().getId());

        assertThat(call(HttpMethod.DELETE, PATH + "/openai", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keys.list(dev.org().getId())).isEmpty();
    }

    @Test
    void badKeysAreRejectedWithoutEchoingThem() {
        Dev dev = developer(true);

        for (String bad : List.of("", "   ", "has a space", "tab\tkey", "x".repeat(513))) {
            var response = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", bad));
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("INVALID_API_KEY");
            if (bad.length() > 3) {
                assertThat(response.getBody().toString()).doesNotContain(bad);
            }
        }
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(keys.list(dev.org().getId())).isEmpty();
        // 512 characters is the largest key that is accepted
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", "x".repeat(512))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownProviderIsRejected() {
        Dev dev = developer(true);

        var response = call(HttpMethod.PUT, PATH + "/google", dev.token(), Map.of("apiKey", KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("UNKNOWN_PROVIDER");
        assertThat(call(HttpMethod.DELETE, PATH + "/google", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oneOrgNeverSeesOrTouchesAnothersKeys() {
        Dev a = developer(true);
        Dev b = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", a.token(), Map.of("apiKey", KEY));

        assertThat(list(b.token()).getBody()).isEmpty();
        call(HttpMethod.DELETE, PATH + "/anthropic", b.token(), null);

        assertThat(keys.reveal(a.org().getId(), LlmVendor.ANTHROPIC)).contains(KEY);
        assertThat(keys.reveal(b.org().getId(), LlmVendor.ANTHROPIC)).isEmpty();
    }

    @Test
    void onlyDevelopersMayUseIt() {
        String buyer = identity.tokenFor(identity.buyerAdmin("buyer-" + UUID.randomUUID() + "@example.com"));

        assertThat(call(HttpMethod.GET, PATH, buyer, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", buyer, Map.of("apiKey", KEY)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, PATH, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
