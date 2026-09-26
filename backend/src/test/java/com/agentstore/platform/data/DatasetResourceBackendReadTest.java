package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetResourceBackendReadTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> get(String path, String token) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, RuntimeFixtures.bearer(token)), Map.class);
    }

    /** 25 rows whose narration has multi-byte characters, so byte offsets differ from character counts. */
    String bigCsv() {
        StringBuilder csv = new StringBuilder("date,narration,amount\n");
        for (int i = 1; i <= 25; i++) {
            csv.append("2026-01-").append(String.format("%02d", i)).append(",row-").append(i).append(" é₹😀,")
                    .append(i).append(".50\n");
        }
        return csv.toString();
    }

    @Test
    void metadataReportsFormatAndRowCount() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));
        fx.pin(agent.run(), "statement_pdf", fx.upload(agent.scenario().buyer(), "jan.pdf", DataFixtures.PDF));

        var csv = get("/runtime/v1/resources/bank_statement", agent.token());
        var pdf = get("/runtime/v1/resources/statement_pdf", agent.token());

        assertThat(csv.getBody().get("format")).isEqualTo("csv");
        assertThat(csv.getBody().get("rowCount")).isEqualTo(2);
        assertThat(pdf.getBody().get("format")).isEqualTo("pdf");
        assertThat(pdf.getBody().get("rowCount")).isNull();
    }

    @Test
    void pagesAcrossBoundariesWithMultiByteText() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "big.csv", bigCsv()));
        List<String> narrations = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            var page = get("/runtime/v1/resources/bank_statement/records?limit=10"
                    + (cursor == null ? "" : "&cursor=" + cursor), agent.token());
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            for (Object record : (List<?>) page.getBody().get("records")) {
                narrations.add((String) ((Map<?, ?>) record).get("narration"));
            }
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(narrations).hasSize(25);
        assertThat(narrations.get(0)).isEqualTo("row-1 é₹😀");
        assertThat(narrations.get(24)).isEqualTo("row-25 é₹😀");
    }

    @Test
    void anExactMultipleOfTheLimitEndsWithANullCursor() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));

        var page = get("/runtime/v1/resources/bank_statement/records?limit=2", agent.token());

        assertThat((List<?>) page.getBody().get("records")).hasSize(2);
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    @Test
    void valuesAreCoercedToTheDeclaredTypes() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));

        var page = get("/runtime/v1/resources/bank_statement/records", agent.token());

        Map<?, ?> rent = (Map<?, ?>) ((List<?>) page.getBody().get("records")).get(1);
        assertThat(((Number) rent.get("amount")).doubleValue()).isEqualTo(-12000.50);
        assertThat(rent.get("date")).isEqualTo("2026-01-06");
        assertThat(rent.get("narration")).isEqualTo("Rent");
    }

    @Test
    void aCursorFromAnotherFileIsRejected() {
        var agent = fx.runningAgent();
        var other = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "a.csv", bigCsv()));
        // different content, so a different sha; identical content would make the cursor valid for both files
        fx.pin(other.run(), "bank_statement", fx.upload(other.scenario().buyer(), "b.csv", bigCsv() + "2026-02-01,extra,1\n"));
        String foreign = (String) get("/runtime/v1/resources/bank_statement/records?limit=5", other.token())
                .getBody().get("nextCursor");

        var response = get("/runtime/v1/resources/bank_statement/records?limit=5&cursor=" + foreign, agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void garbageAndPastTheEndCursorsAreHandled() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);

        assertThat(get("/runtime/v1/resources/bank_statement/records?cursor=!!!", agent.token()).getBody().get("code"))
                .isEqualTo("INVALID_CURSOR");
        assertThat(get("/runtime/v1/resources/bank_statement/records?cursor=bm9wZQ", agent.token()).getBody().get("code"))
                .isEqualTo("INVALID_CURSOR");
        String past = RecordCursor.encode(d.getSha256(), d.getRecordsSizeBytes() + 100);
        var page = get("/runtime/v1/resources/bank_statement/records?cursor=" + past, agent.token());
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) page.getBody().get("records")).isEmpty();
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    @Test
    void aPdfIsNotTabularButItsFileStreamsThroughTheBackend() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "statement_pdf", fx.upload(agent.scenario().buyer(), "jan.pdf", DataFixtures.PDF));

        var records = get("/runtime/v1/resources/statement_pdf/records", agent.token());
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(records.getBody().get("code")).isEqualTo("NOT_TABULAR");

        var link = get("/runtime/v1/resources/statement_pdf/file", agent.token());
        assertThat((String) link.getBody().get("url")).endsWith("/runtime/v1/resources/statement_pdf/file/content");
        var content = rest.exchange("http://localhost:" + port + "/runtime/v1/resources/statement_pdf/file/content",
                HttpMethod.GET, new HttpEntity<>(null, RuntimeFixtures.bearer(agent.token())), byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(content.getBody()).isEqualTo(DataFixtures.PDF);
    }

    @Test
    void aResourceWithNoPinIs404() {
        var agent = fx.runningAgent();

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BOUND");
    }

    @Test
    void theBackendRefusesBuyerDataToASandboxRunEvenIfItWasPinned() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));
        var run = agent.run();
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("DATA_MODE_MISMATCH");
    }

    @Test
    void aReboundOrDeletedFileDoesNotChangeWhatTheRunReads() {
        var agent = fx.runningAgent();
        Dataset first = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", first);
        fx.bind(agent.scenario().connection(), "bank_statement",
                fx.upload(agent.scenario().buyer(), "feb.csv", "date,narration,amount\n2026-02-01,Feb,1\n"));

        var page = get("/runtime/v1/resources/bank_statement/records", agent.token());
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);

        var delete = rest.exchange("http://localhost:" + port + "/api/v1/datasets/" + first.getId(), HttpMethod.DELETE,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anObjectStoreFailureIs503NotAServerError() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);
        storage.deleteQuietly(d.getRecordsKey());   // the blob is gone: the same failure path as an unreachable store

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("STORAGE_UNAVAILABLE");
    }
}
