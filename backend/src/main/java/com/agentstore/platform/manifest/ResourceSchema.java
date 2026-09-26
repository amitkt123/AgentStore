package com.agentstore.platform.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code required} lists column (or key) names; {@code properties} gives the type of the columns that have one. */
public record ResourceSchema(List<String> required, Map<String, ColumnType> properties) {
    public ResourceSchema {
        required = List.copyOf(required);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
