package com.agentstore.platform.execution;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The buyer's run list: one org's connection runs, filtered, searched and paged, plus per-group counts for the filter
 * chips. Plain SQL over named parameters; no user text is ever concatenated into a statement.
 */
@Service
public class RunListService {

    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 100;

    private static final String FROM = """
            FROM run r
            JOIN connection c ON c.id = r.connection_id
            JOIN agent a ON a.id = c.agent_id
            JOIN agent_version v ON v.id = r.agent_version_id
            LEFT JOIN LATERAL (
                SELECT u.full_name FROM gate_request g JOIN app_user u ON u.id = g.decided_by_user_id
                WHERE g.run_id = r.id AND g.decided_by_user_id IS NOT NULL ORDER BY g.decided_at DESC LIMIT 1) d ON true
            WHERE c.buyer_org_id = :orgId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RunListService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunListResponse list(UUID orgId, RunListQuery query) {
        int size = query.size() <= 0 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        int page = Math.max(query.page(), 0);

        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId);
        StringBuilder filters = new StringBuilder();
        if (query.agentId() != null) {
            filters.append(" AND a.id = :agentId");
            params.addValue("agentId", query.agentId());
        }
        if (query.from() != null) {
            filters.append(" AND r.started_at >= :from");
            params.addValue("from", Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            filters.append(" AND r.started_at <= :to");
            params.addValue("to", Timestamp.from(query.to()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            filters.append(" AND (r.display_id ILIKE :q ESCAPE '\\' OR a.name ILIKE :q ESCAPE '\\' "
                    + "OR r.summary ILIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + escapeLike(query.q().trim()) + "%");
        }

        Map<String, Long> counts = counts(filters.toString(), params);
        List<RunStatus> statuses = query.group() != null ? query.group().statuses() : List.of(RunStatus.values());
        long total = query.group() != null ? counts.get(query.group().key())
                : counts.values().stream().mapToLong(Long::longValue).sum();

        MapSqlParameterSource itemParams = new MapSqlParameterSource(params.getValues());
        itemParams.addValue("statuses", statuses.stream().map(Enum::name).toList());
        itemParams.addValue("size", size);
        itemParams.addValue("offset", (long) page * size);
        String sql = "SELECT r.id, r.display_id, a.name AS agent, v.version_number, r.status, r.terminal_reason, "
                + "r.reached_block, r.started_at, r.completed_at, r.total_charged, r.reserved_amount, d.full_name "
                + FROM + filters + " AND r.status IN (:statuses) "
                + "ORDER BY r.started_at DESC NULLS LAST, r.id LIMIT :size OFFSET :offset";
        List<RunListItem> items = jdbc.query(sql, itemParams, (rs, i) -> {
            String reason = rs.getString("terminal_reason");
            String decider = rs.getString("full_name");
            Timestamp started = rs.getTimestamp("started_at");
            Timestamp completed = rs.getTimestamp("completed_at");
            return new RunListItem(rs.getObject("id", UUID.class), rs.getString("display_id"), rs.getString("agent"),
                    rs.getString("version_number"), RunStatus.valueOf(rs.getString("status")),
                    reason == null ? null : TerminalReason.valueOf(reason), rs.getString("reached_block"),
                    started == null ? null : started.toInstant(), completed == null ? null : completed.toInstant(),
                    rs.getBigDecimal("total_charged"), rs.getBigDecimal("reserved_amount"),
                    decider == null ? null : new RunListItem.DecidedBy(decider));
        });
        return new RunListResponse(items, counts, page, size, total);
    }

    /** Group totals under every filter except the group itself. */
    private Map<String, Long> counts(String filters, MapSqlParameterSource params) {
        EnumMap<RunGroup, Long> byGroup = new EnumMap<>(RunGroup.class);
        jdbc.query("SELECT r.status, count(*) AS n " + FROM + filters + " GROUP BY r.status", params, rs -> {
            RunGroup group = RunGroup.of(RunStatus.valueOf(rs.getString("status")));
            byGroup.merge(group, rs.getLong("n"), Long::sum);
        });
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RunGroup g : RunGroup.values()) {
            counts.put(g.key(), byGroup.getOrDefault(g, 0L));
        }
        return counts;
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
