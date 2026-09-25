package com.agentstore.runner.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Persistent budget state. Losing it only resets the count (spec §5). */
public class BudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(BudgetLedger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public record Open(Instant startedAt, int timeoutSeconds) {}

    public String month;
    public long closedSeconds;
    public Map<String, Open> open = new HashMap<>();

    public static BudgetLedger load(Path file) {
        if (!Files.exists(file)) {
            return new BudgetLedger();
        }
        try {
            BudgetLedger ledger = MAPPER.readValue(file.toFile(), BudgetLedger.class);
            if (ledger.open == null) {
                ledger.open = new HashMap<>();
            }
            return ledger;
        } catch (IOException e) {
            log.warn("Budget state file {} is unreadable; starting from zero", file);
            return new BudgetLedger();
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "budget-", ".tmp");
            MAPPER.writeValue(tmp.toFile(), this);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not persist budget state to {}", file, e);
        }
    }
}
