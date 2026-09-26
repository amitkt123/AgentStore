package com.agentstore.platform.data;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * CSV to JSONL of string-valued objects. Handles a BOM, quoting and embedded newlines. A short row's missing cells are
 * null; an over-long row keeps its extra cells under {@link Normalized#EXTRA_KEY} so the schema check can flag it.
 */
public final class CsvNormalizer {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build();

    private CsvNormalizer() {
    }

    public static Normalized normalize(Path in, Path out) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            skipBom(reader);
            try (CSVParser parser = FORMAT.parse(reader)) {
                Iterator<CSVRecord> records = parser.iterator();
                if (!records.hasNext()) {
                    throw new IngestionException("The CSV file is empty; it needs a header row");
                }
                List<String> columns = header(records.next());
                long rows = 0;
                while (records.hasNext()) {
                    CSVRecord record = records.next();
                    ObjectNode row = Jsonl.MAPPER.createObjectNode();
                    for (int i = 0; i < columns.size(); i++) {
                        if (i < record.size()) {
                            row.put(columns.get(i), record.get(i));
                        } else {
                            row.putNull(columns.get(i));
                        }
                    }
                    if (record.size() > columns.size()) {
                        ArrayNode extra = row.putArray(Normalized.EXTRA_KEY);
                        for (int i = columns.size(); i < record.size(); i++) {
                            extra.add(record.get(i));
                        }
                    }
                    writer.write(Jsonl.MAPPER.writeValueAsString(row));
                    writer.write('\n');
                    rows++;
                }
                return new Normalized(rows, columns);
            }
        } catch (CharacterCodingException e) {
            throw new IngestionException("The file is not valid UTF-8 text; export it as CSV (UTF-8)", e);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof CharacterCodingException) {
                throw new IngestionException("The file is not valid UTF-8 text; export it as CSV (UTF-8)", e);
            }
            throw new IngestionException("The CSV file could not be read: " + e.getCause().getMessage(), e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IngestionException("The CSV file is malformed: " + e.getMessage(), e);
        }
    }

    private static List<String> header(CSVRecord record) {
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cell : record) {
            String name = cell.strip();
            if (name.isEmpty()) {
                throw new IngestionException("The header row has a blank column name");
            }
            if (!seen.add(name)) {
                throw new IngestionException("Duplicate column name in the header row: " + name);
            }
            columns.add(name);
        }
        return columns;
    }

    private static void skipBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
    }
}
