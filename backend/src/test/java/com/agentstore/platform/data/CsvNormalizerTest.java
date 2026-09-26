package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvNormalizerTest {

    @TempDir
    Path dir;

    Normalized run(String csv, List<String> jsonlOut) throws Exception {
        return run(csv.getBytes(StandardCharsets.UTF_8), jsonlOut);
    }

    Normalized run(byte[] csv, List<String> jsonlOut) throws Exception {
        Path in = dir.resolve("in.csv");
        Path out = dir.resolve("out.jsonl");
        Files.write(in, csv);
        Normalized n = CsvNormalizer.normalize(in, out);
        jsonlOut.addAll(Files.readAllLines(out));
        return n;
    }

    @Test
    void turnsRowsIntoStringValuedObjects() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("date,narration,amount\n2026-01-05,Salary,50000.00\n2026-01-06,Rent,-12000.50\n", lines);

        assertThat(n.rowCount()).isEqualTo(2);
        assertThat(n.columns()).containsExactly("date", "narration", "amount");
        assertThat(lines).containsExactly(
                "{\"date\":\"2026-01-05\",\"narration\":\"Salary\",\"amount\":\"50000.00\"}",
                "{\"date\":\"2026-01-06\",\"narration\":\"Rent\",\"amount\":\"-12000.50\"}");
    }

    @Test
    void handlesABomQuotingAndEmbeddedNewlinesAndBlankLines() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        String csv = "﻿a,b\n\n\"x, \"\"quoted\"\"\",\"line1\nline2\"\n";
        Normalized n = run(csv, lines);

        assertThat(n.columns()).containsExactly("a", "b");   // BOM not glued to the first header
        assertThat(n.rowCount()).isEqualTo(1);
        assertThat(lines).hasSize(1);                         // the embedded newline is escaped, not a new line
        assertThat(lines.get(0)).isEqualTo("{\"a\":\"x, \\\"quoted\\\"\",\"b\":\"line1\\nline2\"}");
    }

    @Test
    void aShortRowGetsNullCellsAndALongRowIsMarked() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("a,b,c\n1,2\n1,2,3,4,5\n", lines);

        assertThat(n.columns()).containsExactly("a", "b", "c");
        assertThat(lines.get(0)).isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":null}");
        assertThat(lines.get(1)).isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\",\"" + Normalized.EXTRA_KEY + "\":[\"4\",\"5\"]}");
    }

    @Test
    void aHeaderOnlyFileHasZeroRows() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("a,b\n", lines);

        assertThat(n.rowCount()).isZero();
        assertThat(lines).isEmpty();
    }

    @Test
    void rejectsEmptyDuplicateAndBlankHeaders() {
        assertThatThrownBy(() -> run("", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> run("a,a\n1,2\n", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("Duplicate column");
        assertThatThrownBy(() -> run("a,,c\n1,2,3\n", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsBytesThatAreNotUtf8() {
        assertThatThrownBy(() -> run(new byte[]{'a', ',', 'b', '\n', (byte) 0xFF, (byte) 0xFE, ',', '1', '\n'},
                new java.util.ArrayList<>())).isInstanceOf(IngestionException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsAnUnterminatedQuote() {
        assertThatThrownBy(() -> run("a,b\n\"oops,1\n", new java.util.ArrayList<>()))
                .isInstanceOf(IngestionException.class);
    }
}
