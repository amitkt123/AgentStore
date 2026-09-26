package com.agentstore.platform.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** One JSON object per line. Decimals stay exact (12.50 is not rewritten as 12.5 or 1.25E+1). */
public final class Jsonl {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    public static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private Jsonl() {
    }

    public static MappingIterator<Map<String, Object>> readRows(InputStream in) throws IOException {
        return MAPPER.readerFor(ROW).readValues(in);
    }
}
