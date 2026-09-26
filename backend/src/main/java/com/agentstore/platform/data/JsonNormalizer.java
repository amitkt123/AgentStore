package com.agentstore.platform.data;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** A JSON file must be an array of objects; it becomes JSONL with the union of the objects' keys as its columns. */
public final class JsonNormalizer {

    private JsonNormalizer() {
    }

    public static Normalized normalize(Path in, Path out) throws IOException {
        Set<String> columns = new LinkedHashSet<>();
        long rows = 0;
        try (JsonParser parser = Jsonl.MAPPER.createParser(in.toFile());
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IngestionException("The JSON file must be an array of objects");
            }
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    break;
                }
                if (token == null) {
                    throw new IngestionException("The JSON file ends before its array is closed");
                }
                if (token != JsonToken.START_OBJECT) {
                    throw new IngestionException("Item " + (rows + 1) + " is not an object; the file must be an array of objects");
                }
                JsonNode row = Jsonl.MAPPER.readTree(parser);
                row.fieldNames().forEachRemaining(columns::add);
                writer.write(Jsonl.MAPPER.writeValueAsString(row));
                writer.write('\n');
                rows++;
            }
            if (parser.nextToken() != null) {
                throw new IngestionException("The JSON file has content after its array");
            }
        } catch (JsonProcessingException e) {
            throw new IngestionException("The JSON file is not valid: " + e.getOriginalMessage(), e);
        }
        return new Normalized(rows, new ArrayList<>(columns));
    }
}
