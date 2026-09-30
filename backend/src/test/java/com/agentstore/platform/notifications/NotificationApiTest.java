package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.OrgMembership;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationApiTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identities;
    @Autowired JdbcTemplate jdbc;

    private OrgMembership person() {
        return identities.buyerAdmin("centre-" + UUID.randomUUID() + "@example.com");
    }

    private HttpEntity<Object> as(OrgMembership m, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identities.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private UUID seed(OrgMembership m, String category, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notification (id, user_id, org_id, category, title, body, source_event_id) "
                        + "values (?, ?, ?, ?, ?, 'b', ?)",
                id, m.getUser().getId(), m.getOrg().getId(), category, title, UUID.randomUUID());
        return id;
    }

    @Test
    void aPersonSeesOnlyTheirOwnNotificationsInTheirCurrentWorkspace() {
        OrgMembership me = person();
        OrgMembership other = person();
        seed(me, "APPROVAL_NEEDED", "mine");
        seed(other, "APPROVAL_NEEDED", "theirs");

        JsonNode list = rest.exchange(url("/api/v1/notifications"), HttpMethod.GET, as(me, null), JsonNode.class).getBody();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("title").asText()).isEqualTo("mine");
    }

    @Test
    void theFiltersSelectTheirCategories() {
        OrgMembership me = person();
        seed(me, "APPROVAL_NEEDED", "approve");
        seed(me, "RUN_FINISHED", "finished");

        JsonNode needsYou = rest.exchange(url("/api/v1/notifications?filter=needs_you"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        JsonNode agents = rest.exchange(url("/api/v1/notifications?filter=agents"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();

        assertThat(needsYou).hasSize(1);
        assertThat(needsYou.get(0).get("title").asText()).isEqualTo("approve");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("title").asText()).isEqualTo("finished");
        assertThat(rest.exchange(url("/api/v1/notifications?filter=bogus"), HttpMethod.GET, as(me, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unreadCountFallsAsNotificationsAreRead() {
        OrgMembership me = person();
        UUID first = seed(me, "RUN_FINISHED", "a");
        seed(me, "RUN_FINISHED", "b");

        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isEqualTo(2);

        assertThat(rest.exchange(url("/api/v1/notifications/" + first + "/read"), HttpMethod.POST, as(me, null),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isEqualTo(1);

        rest.exchange(url("/api/v1/notifications/read-all"), HttpMethod.POST, as(me, null), Void.class);
        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isZero();
    }

    @Test
    void aPersonCannotMarkSomeoneElsesNotificationRead() {
        OrgMembership me = person();
        OrgMembership other = person();
        UUID theirs = seed(other, "RUN_FINISHED", "theirs");

        assertThat(rest.exchange(url("/api/v1/notifications/" + theirs + "/read"), HttpMethod.POST, as(me, null),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preferencesReturnDefaultsAndAcceptChanges() {
        OrgMembership me = person();

        JsonNode initial = rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        assertThat(initial.get("categories")).hasSize(NotificationCategory.values().length);
        assertThat(initial.get("quietHours").get("enabled").asBoolean()).isFalse();
        assertThat(initial.get("quietHours").get("timezone").asText()).isEqualTo("Asia/Kolkata");

        Map<String, Object> change = Map.of(
                "categories", List.of(Map.of("category", "RUN_FINISHED", "web", true, "email", true)),
                "quietHours", Map.of("enabled", true, "start", "22:00", "end", "07:30", "timezone", "Europe/London"));
        assertThat(rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.PUT, as(me, change), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode after = rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        JsonNode finished = java.util.stream.StreamSupport.stream(after.get("categories").spliterator(), false)
                .filter(c -> c.get("category").asText().equals("RUN_FINISHED")).findFirst().orElseThrow();
        assertThat(finished.get("email").asBoolean()).isTrue();
        assertThat(after.get("quietHours").get("start").asText()).isEqualTo("22:00");
        assertThat(after.get("quietHours").get("timezone").asText()).isEqualTo("Europe/London");
    }

    @Test
    void invalidPreferenceChangesAreRefused() {
        OrgMembership me = person();
        Map<String, Object> webOff = Map.of("categories",
                List.of(Map.of("category", "APPROVAL_NEEDED", "web", false, "email", true)));
        Map<String, Object> badZone = Map.of("quietHours",
                Map.of("enabled", true, "start", "22:00", "end", "07:00", "timezone", "Mars/Olympus"));
        Map<String, Object> emptyWindow = Map.of("quietHours",
                Map.of("enabled", true, "start", "22:00", "end", "22:00", "timezone", "UTC"));

        for (Map<String, Object> body : List.of(webOff, badZone, emptyWindow)) {
            assertThat(rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.PUT, as(me, body), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void theEndpointsRequireAuthentication() {
        assertThat(rest.getForEntity(url("/api/v1/notifications"), String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
