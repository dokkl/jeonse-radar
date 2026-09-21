package com.jeonseradar.ingest.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "molit")
public record MolitProperties(
        String baseUrl,
        String serviceKey,
        int rowsPerPage,
        long delayMs,
        int maxRetries
) {
}
