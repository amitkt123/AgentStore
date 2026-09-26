package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputFormatsTest {

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void headerIsTheUnionOfKeysInFirstSeenOrderAndMissingCellsAreEmpty() {
        String csv = OutputFormats.toCsv(List.of(row("a", 1, "b", "x"), row("c", true, "a", 2)));

        assertThat(csv).isEqualTo("a,b,c\r\n1,x,\r\n2,,true\r\n");
    }

    @Test
    void commasQuotesAndNewlinesAreEscaped() {
        String csv = OutputFormats.toCsv(List.of(row("note", "a,\"b\"\nc")));

        assertThat(csv).isEqualTo("note\r\n\"a,\"\"b\"\"\nc\"\r\n");
    }

    @Test
    void csvDownloadNeutralisesFormulas() {
        String csv = OutputFormats.toCsv(List.of(
                row("v", "=HYPERLINK(\"http://evil\")"), row("v", "+1+1"), row("v", "-2+3"), row("v", "@SUM(A1)"),
                row("v", "\tcmd"), row("v", "\rcmd"), row("v", "plain")));

        assertThat(csv).contains("'=HYPERLINK(").contains("'+1+1").contains("'-2+3").contains("'@SUM(A1)")
                .contains("'\tcmd").contains("plain");
        assertThat(csv).doesNotContain("\r\n=").doesNotContain("\r\n+").doesNotContain("\r\n@");
    }

    @Test
    void realNumbersAreNotPrefixedEvenWhenNegative() {
        String csv = OutputFormats.toCsv(List.of(row("amount", -5), row("amount", new java.math.BigDecimal("-12.50"))));

        assertThat(csv).isEqualTo("amount\r\n-5\r\n-12.50\r\n");
    }

    @Test
    void nestedValuesBecomeJsonText() {
        String csv = OutputFormats.toCsv(List.of(row("tags", List.of("a", "b"))));

        assertThat(csv).isEqualTo("tags\r\n\"[\"\"a\"\",\"\"b\"\"]\"\r\n");
    }

    @Test
    void jsonlLinesBecomeAJsonArray() {
        assertThat(OutputFormats.toJsonArray(List.of("{\"a\":1}", "{\"a\":2}"))).isEqualTo("[{\"a\":1},{\"a\":2}]");
        assertThat(OutputFormats.toJsonArray(List.of())).isEqualTo("[]");
    }
}
