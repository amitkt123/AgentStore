package com.agentstore.platform.execution;

import com.agentstore.platform.data.DataApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/** The per-agent monthly limit: what a connection has cost this UTC month against {@code monthly_limit}. */
@Component
public class ConnectionLimits {

    public static final String AGENT_MONTHLY_LIMIT = "AGENT_MONTHLY_LIMIT";

    private final ConnectionRepository connections;
    private final RunRepository runs;

    public ConnectionLimits(ConnectionRepository connections, RunRepository runs) {
        this.connections = connections;
        this.runs = runs;
    }

    /**
     * Refuses with 402 {@code AGENT_MONTHLY_LIMIT} when a run of {@code spendCap} would take the connection past its
     * limit. Locks the connection row, so it must run in the same transaction that then saves the run.
     */
    @Transactional
    public void enforce(UUID connectionId, BigDecimal spendCap) {
        Connection connection = connections.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        BigDecimal limit = connection.getMonthlyLimit();
        if (limit != null && monthToDate(connectionId).add(spendCap).compareTo(limit) > 0) {
            throw new DataApiException(HttpStatus.PAYMENT_REQUIRED, AGENT_MONTHLY_LIMIT,
                    "This run could take the agent past its monthly limit of " + limit);
        }
    }

    public BigDecimal monthToDate(UUID connectionId) {
        return runs.spendSince(connectionId, monthStart());
    }

    static Instant monthStart() {
        return ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
