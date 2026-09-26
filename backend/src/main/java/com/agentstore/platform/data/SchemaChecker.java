package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates rows against a resource's schema (or, with none, only that there are rows). Pure: it reads a row iterator
 * and saves nothing. Rows are numbered from 1, not counting the header; row 0 means "the file as a whole".
 */
public final class SchemaChecker {

    public static final int MAX_ERRORS = 50;
    private static final int MAX_VALUE_CHARS = 40;

    public record RowError(long row, String column, String message) {
    }

    public record CheckResult(boolean ok, long rowCount, boolean columnsMatch, List<String> missingColumns,
                              long rowErrorCount, List<RowError> rowErrors) {

        /** The file is the wrong kind for the resource (a PDF where rows are expected, or the reverse). */
        public static CheckResult formatMismatch(String message) {
            return new CheckResult(false, 0, true, List.of(), 1, List.of(new RowError(0, null, message)));
        }

        /** A PDF for a resource that reads a document: nothing further to check. */
        public static CheckResult file() {
            return new CheckResult(true, 0, true, List.of(), 0, List.of());
        }
    }

    private SchemaChecker() {
    }

    public static CheckResult check(ResourceSchema schema, List<String> columns, Iterator<Map<String, Object>> rows) {
        List<String> required = schema == null ? List.of() : schema.required();
        List<String> missing = required.stream().filter(c -> !columns.contains(c)).toList();
        List<RowError> errors = new ArrayList<>();
        long errorCount = 0;
        long n = 0;
        while (rows.hasNext()) {
            n++;
            Map<String, Object> row = rows.next();
            if (row.containsKey(Normalized.EXTRA_KEY)) {
                errorCount++;
                add(errors, n, null, "Row has more cells than the header");
            }
            if (schema == null) {
                continue;
            }
            for (String column : required) {
                if (columns.contains(column) && !row.containsKey(column)) {
                    errorCount++;
                    add(errors, n, column, "Missing required column '" + column + "'");
                }
            }
            for (Map.Entry<String, ColumnType> property : schema.properties().entrySet()) {
                String column = property.getKey();
                if (row.containsKey(column) && !ValueCoercer.canCoerce(property.getValue(), row.get(column))) {
                    errorCount++;
                    add(errors, n, column, "'" + shorten(row.get(column)) + "' is not a valid "
                            + property.getValue().wire());
                }
            }
        }
        if (n == 0) {
            errorCount++;
            errors.add(new RowError(0, null, "The file has no data rows"));
        }
        boolean columnsMatch = missing.isEmpty();
        return new CheckResult(columnsMatch && errorCount == 0, n, columnsMatch, missing, errorCount, List.copyOf(errors));
    }

    private static void add(List<RowError> errors, long row, String column, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new RowError(row, column, message));
        }
    }

    private static String shorten(Object value) {
        String text = String.valueOf(value);
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS) + "…";
    }
}
