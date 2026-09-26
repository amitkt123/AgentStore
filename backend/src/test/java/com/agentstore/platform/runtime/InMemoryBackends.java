package com.agentstore.platform.runtime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Working ResourceBackend / LlmBackend for tests; @Primary so they win over the 501 defaults. */
@TestConfiguration
public class InMemoryBackends {

    @Bean
    @Primary
    InMemoryResourceBackend inMemoryResourceBackend() {
        return new InMemoryResourceBackend();
    }

    @Bean
    @Primary
    InMemoryLlmBackend inMemoryLlmBackend() {
        return new InMemoryLlmBackend();
    }

    public static class InMemoryResourceBackend implements ResourceBackend {
        private final Map<String, List<Map<String, Object>>> data = new HashMap<>();
        public final List<Map<String, Object>> written = new ArrayList<>();

        public void seed(String resource, List<Map<String, Object>> rows) {
            data.put(resource, rows);
        }

        @Override
        public ResourceMeta metadata(RunPrincipal principal, String resource) {
            return new ResourceMeta(resource, "csv", (long) data.getOrDefault(resource, List.of()).size());
        }

        @Override
        public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
            List<Map<String, Object>> rows = data.getOrDefault(resource, List.of());
            return new RecordPage(rows.subList(0, Math.min(limit, rows.size())), null);
        }

        @Override
        public FileLink file(RunPrincipal principal, String resource) {
            return new FileLink("https://storage.example/" + resource, Instant.now().plusSeconds(60));
        }

        @Override
        public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                 boolean requiresApproval) {
            written.addAll(records);
            return new WriteResult(records.size(), requiresApproval);
        }
    }

    public static class InMemoryLlmBackend implements LlmBackend {
        @Override
        public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request) {
            return Map.of("content", "echo", "model", String.valueOf(request.getOrDefault("model", "test")));
        }
    }
}
