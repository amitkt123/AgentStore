package com.agentstore.platform.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders an output's records for download. A blob is at most 5 MB, so this works in memory. */
public final class OutputFormats {

    private OutputFormats() {
    }

    /**
     * RFC 4180 CSV with CRLF line ends. Agent-written text is untrusted: a string cell that a spreadsheet would read as
     * a formula (leading {@code = + - @}, tab or CR) gets a leading apostrophe. Real JSON numbers stay as they are, so
     * {@code -5} is still a number.
     */
    public static String toCsv(List<Map<String, Object>> rows) {
        Set<String> header = new LinkedHashSet<>();
        rows.forEach(r -> header.addAll(r.keySet()));
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", header.stream().map(OutputFormats::cell).toList())).append("\r\n");
        for (Map<String, Object> row : rows) {
            boolean first = true;
            for (String column : header) {
                if (!first) {
                    csv.append(',');
                }
                first = false;
                csv.append(cell(row.get(column)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    public static String toJsonArray(List<String> jsonlLines) {
        return "[" + String.join(",", jsonlLines) + "]";
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        boolean textual;
        if (value instanceof Number || value instanceof Boolean) {
            text = value.toString();
            textual = false;
        } else if (value instanceof String s) {
            text = s;
            textual = true;
        } else {
            try {
                text = Jsonl.MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                text = String.valueOf(value);
            }
            textual = true;
        }
        if (textual && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
