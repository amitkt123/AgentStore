package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** E2B lifecycle over REST. Env is NOT sent here: it goes to the started process via the shim only. */
public class E2bRestClient {

    /** E2B: allow rules take precedence over deny rules, so deny-all + allowOut = allowlist only. */
    static final String ALL_TRAFFIC = "0.0.0.0/0";

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
        Map<?, ?> response = client.post().uri("/sandboxes")
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

    public List<OwnedSandbox> list(String runnerId) {
        List<?> rows = client.get().uri("/sandboxes").header("X-API-Key", apiKey).retrieve().body(List.class);
        List<OwnedSandbox> owned = new ArrayList<>();
        if (rows == null) {
            return owned;
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
        return owned;
    }
}
