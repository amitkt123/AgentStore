package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class RunListTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runs;
    @Autowired
    GateService gateService;
    @Autowired
    UserRepository users;

    private JsonNode get(ExecutionFixtures.Scenario s, String query) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_ADMIN));
        return rest.exchange("http://localhost:" + port + "/api/v1/runs" + query, HttpMethod.GET, new HttpEntity<>(h),
                JsonNode.class).getBody();
    }

    private ExecutionFixtures.Scenario fourRuns() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        fixtures.run(s, RunStatus.AWAITING_APPROVAL, "sbx-l1", "tok-l1-" + System.nanoTime());
        fixtures.run(s, RunStatus.RUNNING, "sbx-l2", "tok-l2-" + System.nanoTime());
        fixtures.run(s, RunStatus.COMPLETED, "sbx-l3", "tok-l3-" + System.nanoTime());
        Run failed = fixtures.run(s, RunStatus.FAILED, "sbx-l4", "tok-l4-" + System.nanoTime());
        failed.setSummary("Ledger export failed");
        runs.save(failed);
        return s;
    }

    @Test
    void countsCoverEveryGroupAndIgnoreTheGroupFilter() {
        var s = fourRuns();
        JsonNode all = get(s, "");
        assertThat(all.get("items")).hasSize(4);
        JsonNode counts = all.get("counts");
        assertThat(counts.get("needs_you").asInt()).isEqualTo(1);
        assertThat(counts.get("running").asInt()).isEqualTo(1);
        assertThat(counts.get("finished").asInt()).isEqualTo(1);
        assertThat(counts.get("stopped").asInt()).isEqualTo(1);

        JsonNode stopped = get(s, "?group=stopped");
        assertThat(stopped.get("items")).hasSize(1);
        assertThat(stopped.get("counts")).isEqualTo(counts);
    }

    @Test
    void searchMatchesDisplayIdAndSummaryAndTreatsWildcardsLiterally() {
        var s = fourRuns();
        assertThat(get(s, "?q=ledger").get("items")).hasSize(1);
        assertThat(get(s, "?q=%25").get("items")).isEmpty();          // a literal % matches nothing
        String someId = get(s, "").get("items").get(0).get("displayId").asText().toLowerCase();
        assertThat(get(s, "?q=" + someId).get("items")).hasSize(1);
    }

    @Test
    void dateRangeAndPagingEdges() {
        var s = fourRuns();
        assertThat(get(s, "?from=2999-01-01T00:00:00Z").get("items")).isEmpty();
        assertThat(get(s, "?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z").get("items")).isEmpty();
        JsonNode beyond = get(s, "?page=9&size=2");
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("counts").get("running").asInt()).isEqualTo(1);
        assertThat(get(s, "?size=1000").get("size").asInt()).isEqualTo(100);
    }

    @Test
    void pagesThroughThirtyRunsAndFiltersByAgent() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        for (int i = 0; i < 30; i++) {
            fixtures.run(s, RunStatus.COMPLETED, "sbx-p" + i, "tok-p" + i + "-" + System.nanoTime());
        }
        JsonNode page = get(s, "?size=10&page=2");
        assertThat(page.get("items")).hasSize(10);
        assertThat(page.get("total").asInt()).isEqualTo(30);
        JsonNode other = get(s, "?agentId=" + UUID.randomUUID());
        assertThat(other.get("items")).isEmpty();
        assertThat(other.get("counts").get("finished").asInt()).isZero();
        assertThat(get(s, "?agentId=" + s.connection().getAgent().getId()).get("items")).hasSize(25);   // default page size
    }

    @Test
    void otherOrgsSandboxRunsAndDevelopersAreExcluded() {
        var mine = fourRuns();
        fourRuns();   // another org's runs must not leak in
        Run sandbox = new Run(null, null, mine.version(), RunStatus.RUNNING, new BigDecimal("100.00"), BigDecimal.ZERO,
                "sbx-sb", RunTokenService.hash("tok-sb-" + System.nanoTime()));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(mine.version().getAgent().getDeveloperOrg().getId());
        runs.save(sandbox);

        assertThat(get(mine, "").get("items")).hasSize(4);
        HttpHeaders dev = new HttpHeaders();
        dev.setBearerAuth(jwtService.issue(UUID.randomUUID(), mine.buyer().getId(), UserRole.DEVELOPER));
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/runs", HttpMethod.GET, new HttpEntity<>(dev),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void decidedByIsTheLatestDeciderOrNull() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-db", "tok-db-" + System.nanoTime());
        assertThat(get(s, "").get("items").get(0).get("decidedBy").isNull()).isTrue();

        User decider = users.save(new User(null, "decider-" + System.nanoTime() + "@example.com", null, "Dana Decider"));
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        gateService.decide(run.getId(), null, true, decider.getId(), "ok", DecisionChannel.WEB);
        assertThat(get(s, "").get("items").get(0).get("decidedBy").get("name").asText()).isEqualTo("Dana Decider");
    }
}
