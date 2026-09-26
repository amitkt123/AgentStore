package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCheckerTest {

    static final ResourceSchema SCHEMA = new ResourceSchema(List.of("date", "narration", "amount"),
            Map.of("date", ColumnType.DATE, "amount", ColumnType.NUMBER));
    static final List<String> COLUMNS = List.of("date", "narration", "amount");

    static Map<String, Object> row(Object date, Object narration, Object amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("narration", narration);
        row.put("amount", amount);
        return row;
    }

    SchemaChecker.CheckResult check(ResourceSchema schema, List<String> columns, List<Map<String, Object>> rows) {
        return SchemaChecker.check(schema, columns, rows.iterator());
    }

    @Test
    void aMatchingFilePasses() {
        var result = check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "Salary", "50000.00"), row("2026-01-06", "Rent", "-1.5")));

        assertThat(result.ok()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columnsMatch()).isTrue();
        assertThat(result.missingColumns()).isEmpty();
        assertThat(result.rowErrors()).isEmpty();
    }

    @Test
    void reportsMissingRequiredColumns() {
        var result = check(SCHEMA, List.of("date", "amount"), List.of(row("2026-01-05", null, "1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.columnsMatch()).isFalse();
        assertThat(result.missingColumns()).containsExactly("narration");
    }

    @Test
    void reportsRowAndColumnForEveryBadValueButOnlyTheFirst50() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(row("2026-01-05", "n", "abc"));
        }
        var result = check(SCHEMA, COLUMNS, rows);

        assertThat(result.ok()).isFalse();
        assertThat(result.rowErrorCount()).isEqualTo(60);
        assertThat(result.rowErrors()).hasSize(50);
        assertThat(result.rowErrors().get(0)).isEqualTo(new SchemaChecker.RowError(1, "amount",
                "'abc' is not a valid number"));
        assertThat(result.rowErrors().get(49).row()).isEqualTo(50);
    }

    @Test
    void anOverLongCsvRowAndAMissingRequiredKeyAreRowErrors() {
        Map<String, Object> longRow = row("2026-01-05", "n", "1");
        longRow.put(Normalized.EXTRA_KEY, List.of("x"));
        Map<String, Object> missingKey = new LinkedHashMap<>();
        missingKey.put("date", "2026-01-05");
        missingKey.put("amount", "1");     // 'narration' is a column of the file but absent from this JSON row

        var result = check(SCHEMA, COLUMNS, List.of(longRow, missingKey));

        assertThat(result.rowErrors()).extracting(SchemaChecker.RowError::row).containsExactly(1L, 2L);
        assertThat(result.rowErrors().get(0).message()).contains("more cells than the header");
        assertThat(result.rowErrors().get(1).column()).isEqualTo("narration");
    }

    @Test
    void anEmptyFileFailsBecauseItHasNoRows() {
        var result = check(SCHEMA, COLUMNS, List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.rowCount()).isZero();
        assertThat(result.rowErrors()).extracting(SchemaChecker.RowError::message).containsExactly("The file has no data rows");
    }

    @Test
    void anUntypedResourceOnlyNeedsRows() {
        assertThat(check(null, List.of("anything"), List.of(Map.of("anything", "x"))).ok()).isTrue();
        assertThat(check(null, List.of("anything"), List.of()).ok()).isFalse();
    }

    @Test
    void nullAndBlankCellsAreFineForTypedColumns() {
        assertThat(check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "n", null), row("", "n", " "))).ok()).isTrue();
    }

    @Test
    void longValuesAreTruncatedInTheMessage() {
        var result = check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "n", "x".repeat(500))));

        assertThat(result.rowErrors().get(0).message().length()).isLessThan(100);
    }
}
