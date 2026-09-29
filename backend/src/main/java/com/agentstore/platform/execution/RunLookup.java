package com.agentstore.platform.execution;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Resolves a run reference that is either a UUID or a display ID such as R-2291 or S-121. */
@Component
public class RunLookup {

    private final RunRepository runs;

    public RunLookup(RunRepository runs) {
        this.runs = runs;
    }

    public Run find(String ref) {
        String trimmed = ref == null ? "" : ref.trim();
        Optional<Run> found = parseUuid(trimmed).flatMap(runs::findById);
        if (found.isEmpty()) {
            found = runs.findByDisplayId(trimmed.toUpperCase());
        }
        return found.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    public UUID idOf(String ref) {
        return find(ref).getId();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
