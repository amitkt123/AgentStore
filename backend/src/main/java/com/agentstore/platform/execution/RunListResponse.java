package com.agentstore.platform.execution;

import java.util.List;
import java.util.Map;

public record RunListResponse(List<RunListItem> items, Map<String, Long> counts, int page, int size, long total) {
}
