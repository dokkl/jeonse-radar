package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class IngestTargetPlanner {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    public record IngestTarget(ApiType apiType, String regionCode, String dealYm) {
    }

    private final SigunguCatalog catalog;
    private final int rentBackfillMonths;
    private final int tradeBackfillMonths;
    private final int incrementalMonths;

    public IngestTargetPlanner(SigunguCatalog catalog,
                               @Value("${molit.backfill.rent-months:36}") int rentBackfillMonths,
                               @Value("${molit.backfill.trade-months:24}") int tradeBackfillMonths,
                               @Value("${molit.incremental-months:3}") int incrementalMonths) {
        this.catalog = catalog;
        this.rentBackfillMonths = rentBackfillMonths;
        this.tradeBackfillMonths = tradeBackfillMonths;
        this.incrementalMonths = incrementalMonths;
    }

    public List<IngestTarget> backfillTargets() {
        List<IngestTarget> targets = new ArrayList<>();
        YearMonth current = YearMonth.now();
        addTargets(targets, ApiType.RENT, current, rentBackfillMonths);
        addTargets(targets, ApiType.TRADE, current, tradeBackfillMonths);
        return targets;
    }

    /**
     * 신고 기한이 계약일로부터 30일이고 해제여부·등기일자가 나중에 바뀌므로,
     * 당월 포함 직전 3개월을 매일 다시 긁는다 (스펙 §1.2).
     */
    public List<IngestTarget> incrementalTargets(LocalDate today) {
        List<IngestTarget> targets = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        addTargets(targets, ApiType.RENT, current, incrementalMonths);
        addTargets(targets, ApiType.TRADE, current, incrementalMonths);
        return targets;
    }

    private void addTargets(List<IngestTarget> targets, ApiType apiType,
                            YearMonth from, int months) {
        for (String regionCode : catalog.all()) {
            for (int i = 0; i < months; i++) {
                targets.add(new IngestTarget(apiType, regionCode, from.minusMonths(i).format(YM)));
            }
        }
    }
}
