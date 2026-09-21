package com.jeonseradar.ingest.parser;

import java.util.List;
import java.util.Map;

public record ParsedResponse(
        ResultCode code,
        String rawCode,
        int totalCount,
        List<Map<String, String>> items
) {
    public boolean hasMorePages(int pageNo, int rowsPerPage) {
        return (long) pageNo * rowsPerPage < totalCount;
    }
}
