package com.agentstore.platform.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/** Records a {@code reconciliation_issue} for today (UTC), once per (day, kind, provider_ref). */
@Component
public class ReconciliationIssues {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReconciliationIssues(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String kind, String providerRef, Map<String, Object> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        jdbc.update("insert into reconciliation_issue (id, day, kind, provider_ref, detail) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?::jsonb) on conflict (day, kind, provider_ref) do nothing",
                Date.valueOf(LocalDate.now(ZoneOffset.UTC)), kind, providerRef, json);
    }
}
