package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * E2B lifecycle over REST. Env is NOT sent here: it goes to the started process via the shim only.
 *
 * <p>Create and list use the v2 endpoints, as e2b SDK 2.51.0 does. {@code POST /v2/sandboxes} always
 * secures envd with an access token (the deprecated v1 create left it open unless {@code secure: true}).
 * The runner never needs that token: the shim's {@code Sandbox.connect} gets it from
 * {@code POST /v2/sandboxes/{id}/connect}.
 */
public class E2bRestClient {

    /** E2B: allow rules take precedence over deny rules, so deny-all + allowOut = allowlist only. */
    static final String ALL_TRAFFIC = "0.0.0.0/0";

    /** Pagination cursor header on {@code GET /v2/sandboxes}; absent on the last page. */
    static final String NEXT_TOKEN_HEADER = "X-Next-Token";

    private final RestClient client;
    private final String apiKey;

    public E2bRestClient(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public String create(SandboxLaunch launch) {
        Map<String, Object> body = Map.of(
                "templateID", launch.imageRef(),
                "timeout", launch.timeoutSeconds(),
                "metadata", launch.labels(),
                "network", Map.of("allowOut", launch.allowOut(), "denyOut", List.of(ALL_TRAFFIC)));
        Map<?, ?> response = client.post().uri("/v2/sandboxes")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return (String) response.get("sandboxID");
    }

    public boolean exists(String sandboxId) {
        try {
            client.get().uri("/sandboxes/{id}", sandboxId).header("X-API-Key", apiKey).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public void delete(String sandboxId) {
        try {
            client.delete().uri("/sandboxes/{id}", sandboxId).header("X-API-Key", apiKey).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // already gone
        }
    }

    /**
     * Lists this runner's sandboxes. The server filters on the runnerId label (same query format as the
     * SDK: {@code key=value}, each part URL-encoded). The runner then checks the label again on each row.
     * It follows {@code X-Next-Token} until the last page.
     */
    public List<OwnedSandbox> list(String runnerId) {
        String metadataQuery = encode(Labels.RUNNER_ID) + "=" + encode(runnerId);
        List<OwnedSandbox> owned = new ArrayList<>();
        String nextToken = null;
        do {
            String token = nextToken;
            ResponseEntity<List> page = client.get()
                    .uri(b -> {
                        b.path("/v2/sandboxes").queryParam("metadata", "{metadata}");
                        if (token != null) {
                            b.queryParam("nextToken", "{nextToken}");
                            return b.build(metadataQuery, token);
                        }
                        return b.build(metadataQuery);
                    })
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .toEntity(List.class);
            collectOwned(page.getBody(), runnerId, owned);
            nextToken = page.getHeaders().getFirst(NEXT_TOKEN_HEADER);
        } while (StringUtils.hasText(nextToken));
        return owned;
    }

    private static String encode(String s) {
        return UriUtils.encode(s, StandardCharsets.UTF_8);
    }

    private static void collectOwned(List<?> rows, String runnerId, List<OwnedSandbox> owned) {
        if (rows == null) {
            return;
        }
        for (Object row : rows) {
            Map<?, ?> m = (Map<?, ?>) row;
            Object metadata = m.get("metadata");
            if (!(metadata instanceof Map<?, ?> md) || !runnerId.equals(md.get(Labels.RUNNER_ID))) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            md.forEach((k, v) -> labels.put(String.valueOf(k), String.valueOf(v)));
            owned.add(new OwnedSandbox((String) m.get("sandboxID"), labels));
        }
    }
}
