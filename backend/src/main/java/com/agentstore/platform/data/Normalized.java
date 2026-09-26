package com.agentstore.platform.data;

import java.util.List;

/** What normalizing a file produced. {@code EXTRA_KEY} marks a CSV row that had more cells than the header. */
public record Normalized(long rowCount, List<String> columns) {
    public static final String EXTRA_KEY = "__extra_cells__";
}
