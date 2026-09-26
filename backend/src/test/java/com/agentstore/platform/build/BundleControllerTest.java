package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BundleControllerTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired AgentBundleRepository bundles;
    @Autowired TemplateBuildRepository builds;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    record Dev(String token, Agent agent) {}

    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        return new Dev(identity.tokenFor(m), agent);
    }

    ResponseEntity<String> post(Dev dev, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dev.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    JsonNode read(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    JsonNode createUpload(Dev dev, byte[] zip) throws Exception {
        var response = post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/uploads",
                Map.of("sha256", TestBundles.sha256(zip), "sizeBytes", zip.length));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response);
    }

    void upload(String url, byte[] zip) throws Exception {
        HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
    }

    ResponseEntity<String> finalizeBundle(Dev dev, String bundleId, String sha) {
        return post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/" + bundleId + "/finalize", Map.of("sha256", sha));
    }

    ResponseEntity<String> push(Dev dev, byte[] zip) throws Exception {
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), zip);
        return finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));
    }

    @Test
    void aGoodBundleCreatesADraftVersionAndQueuesABuild() throws Exception {
        Dev dev = developer();
        var response = push(dev, TestBundles.python("1.0.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.get("versionNumber").asText()).isEqualTo("1.0.0");
        assertThat(body.get("buildStatus").asText()).isEqualTo("QUEUED");

        AgentVersion version = versions.findById(UUID.fromString(body.get("versionId").asText())).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(version.getBuildStatus()).isEqualTo(BuildStatus.QUEUED);
        assertThat(version.getImageRef()).isNull();
        assertThat(version.getBundle().getId().toString()).isEqualTo(body.get("bundleId").asText());

        TemplateBuild build = builds.findById(UUID.fromString(body.get("buildId").asText())).orElseThrow();
        assertThat(build.getBackend()).isEqualTo("e2b");
        assertThat(build.getRuntime()).isEqualTo("python-3.12");
        assertThat(build.getEntrypoint()).isEqualTo("agent/main.py");
        assertThat(build.getLogStorageKey()).isEqualTo("builds/" + build.getId() + ".log");
        assertThat(bundles.findById(version.getBundle().getId()).orElseThrow().getStatus()).isEqualTo(BundleStatus.UPLOADED);
    }

    @Test
    void finalizingTwiceReturnsTheSameBuild() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), zip);
        JsonNode first = read(finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip)));
        JsonNode second = read(finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip)));
        assertThat(second.get("buildId")).isEqualTo(first.get("buildId"));
        assertThat(second.get("versionId")).isEqualTo(first.get("versionId"));
    }

    @Test
    void concurrentFinalizesOfOneBundleQueueOneBuild() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), zip);
        String bundleId = upload.get("bundleId").asText();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> finalizeBundle(dev, bundleId, TestBundles.sha256(zip))));
            }
            java.util.Set<String> buildIds = new java.util.HashSet<>();
            for (var call : calls) {
                var response = call.get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                buildIds.add(read(response).get("buildId").asText());
            }
            assertThat(buildIds).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(builds.findAll().stream().filter(b -> b.getBundle().getId().toString().equals(bundleId))).hasSize(1);
    }

    @Test
    void finalizingBeforeTheUploadArrivesIsAConflictAndKeepsTheBundle() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(dev, zip);
        var response = finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bundles.findById(UUID.fromString(upload.get("bundleId").asText())).orElseThrow().getStatus())
                .isEqualTo(BundleStatus.UPLOADING);
    }

    @Test
    void bytesThatDoNotMatchTheDeclaredShaAreRejectedAndDeleted() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        byte[] other = TestBundles.python("9.9.9");
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), other);   // same length, different bytes
        var response = finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(response).get("code").asText()).isEqualTo("INVALID_BUNDLE");
        AgentBundle bundle = bundles.findById(UUID.fromString(upload.get("bundleId").asText())).orElseThrow();
        assertThat(bundle.getStatus()).isEqualTo(BundleStatus.REJECTED);
        assertThat(storage.size(bundle.getStorageKey())).isEmpty();
    }

    @Test
    void manifestProblemsAreRejectedWithAReadableReason() throws Exception {
        Dev dev = developer();
        Map<String, String> noRuntime = new LinkedHashMap<>();
        noRuntime.put("manifest.json", TestBundles.manifest("1.0.0").replace("\"runtime\": \"python-3.12\",", ""));
        noRuntime.put("agent/main.py", "x");
        var r1 = push(dev, TestBundles.zip(noRuntime));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(r1).get("message").asText()).contains("runtime is required");

        var r2 = push(dev, TestBundles.zip(Map.of("manifest.json", TestBundles.manifest("1.0.0"))));
        assertThat(read(r2).get("message").asText()).contains("Entrypoint agent/main.py is not in the bundle");

        var r3 = push(dev, TestBundles.zip(Map.of("agent/main.py", "x")));
        assertThat(read(r3).get("message").asText()).contains("manifest.json is missing");
    }

    @Test
    void aDeclaredSizeOver100MbIsRefusedUpFront() {
        Dev dev = developer();
        var response = post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/uploads",
                Map.of("sha256", "a".repeat(64), "sizeBytes", BundleService.MAX_BUNDLE_BYTES + 1));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void anotherOrgsAgentIsForbidden() throws Exception {
        Dev owner = developer();
        Dev intruder = developer();
        var response = post(intruder, "/api/v1/agents/" + owner.agent().getId() + "/bundles/uploads",
                Map.of("sha256", "a".repeat(64), "sizeBytes", 10));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(owner, zip);
        var finalize = post(intruder, "/api/v1/agents/" + owner.agent().getId() + "/bundles/"
                + upload.get("bundleId").asText() + "/finalize", Map.of("sha256", TestBundles.sha256(zip)));
        assertThat(finalize.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void pushingToASubmittedVersionIsRejectedAndChangesNothing() throws Exception {
        Dev dev = developer();
        JsonNode first = read(push(dev, TestBundles.python("1.0.0")));
        AgentVersion version = versions.findById(UUID.fromString(first.get("versionId").asText())).orElseThrow();
        version.setStatus(VersionStatus.IN_REVIEW);
        versions.save(version);

        var again = push(dev, TestBundles.python("1.0.0"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(again).get("message").asText()).contains("already IN_REVIEW");
        assertThat(versions.findById(version.getId()).orElseThrow().getBundle().getId().toString())
                .isEqualTo(first.get("bundleId").asText());
    }

    @Test
    void pushingAgainToADraftSupersedesTheQueuedBuild() throws Exception {
        Dev dev = developer();
        JsonNode first = read(push(dev, TestBundles.python("1.0.0")));
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("manifest.json", TestBundles.manifest("1.0.0"));
        changed.put("agent/main.py", "print('v2')\n");
        JsonNode second = read(push(dev, TestBundles.zip(changed)));

        assertThat(second.get("versionId")).isEqualTo(first.get("versionId"));
        TemplateBuild old = builds.findById(UUID.fromString(first.get("buildId").asText())).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(old.getFailureCode()).isEqualTo(BuildFailureCodes.SUPERSEDED);
        AgentVersion version = versions.findById(UUID.fromString(second.get("versionId").asText())).orElseThrow();
        assertThat(version.getBundle().getId().toString()).isEqualTo(second.get("bundleId").asText());
    }
}
