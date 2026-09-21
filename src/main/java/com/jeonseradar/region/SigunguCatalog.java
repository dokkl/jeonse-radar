package com.jeonseradar.region;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SigunguCatalog {

    private final Map<String, String> codeToName = new LinkedHashMap<>();

    public SigunguCatalog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("sigungu.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                if (parts.length == 2 && parts[0].trim().matches("\\d{5}")) {
                    codeToName.putIfAbsent(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("sigungu.csv를 읽을 수 없습니다", e);
        }
    }

    public List<String> all() {
        return new ArrayList<>(codeToName.keySet());
    }

    public String nameOf(String code) {
        return codeToName.get(code);
    }
}
