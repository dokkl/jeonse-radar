package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class IngestTargetPlanner {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    public record IngestTarget(ApiType apiType, String regionCode, String dealYm) {
    }

    private final SigunguCatalog catalog;
    private final IngestLogRepository logRepository;
    private final int rentBackfillMonths;
    private final int tradeBackfillMonths;
    private final int incrementalMonths;

    public IngestTargetPlanner(SigunguCatalog catalog,
                               IngestLogRepository logRepository,
                               @Value("${molit.backfill.rent-months:36}") int rentBackfillMonths,
                               @Value("${molit.backfill.trade-months:24}") int tradeBackfillMonths,
                               @Value("${molit.incremental-months:3}") int incrementalMonths) {
        this.catalog = catalog;
        this.logRepository = logRepository;
        this.rentBackfillMonths = rentBackfillMonths;
        this.tradeBackfillMonths = tradeBackfillMonths;
        this.incrementalMonths = incrementalMonths;
    }

    /**
     * 백필은 일회성이다 — 이미 SUCCESS로 끝난 대상까지 재실행 때마다 다시 긁으면,
     * 하루 쿼터를 완료분 재수집에 태워 정작 남은 대상은 영영 못 부른다
     * (2026-09-23 오전 실측: 대상 16140건을 그대로 다시 돌려 9분 만에 이미 SUCCESS였던
     * 290건을 재수집하고 킬됨). PENDING/FAILED/미기록은 재개 대상이므로 그대로 둔다.
     * SUCCESS 집합은 건별 조회가 아니라 한 번의 쿼리로 가져와 메모리에서 거른다.
     */
    public List<IngestTarget> backfillTargets() {
        List<IngestTarget> all = enumerate(rentBackfillMonths, tradeBackfillMonths, YearMonth.now());
        Set<IngestTarget> succeeded = succeededTargets();
        return all.stream().filter(t -> !succeeded.contains(t)).toList();
    }

    /** BackfillCommand가 로그에 "스킵 N건"을 보고하기 위한 전체 모수 — DB 조회 없이 계산한다. */
    public int backfillTargetCount() {
        return catalog.all().size() * (rentBackfillMonths + tradeBackfillMonths);
    }

    private Set<IngestTarget> succeededTargets() {
        Set<IngestTarget> succeeded = new HashSet<>();
        for (IngestLogRepository.SuccessKey key : logRepository.findKeysByStatus(IngestStatus.SUCCESS)) {
            succeeded.add(new IngestTarget(key.getApiType(), key.getRegionCode(), key.getDealYm()));
        }
        return succeeded;
    }

    /**
     * 신고 기한이 계약일로부터 30일이고 해제여부·등기일자가 나중에 바뀌므로,
     * 당월 포함 직전 3개월을 매일 다시 긁는다 (스펙 §1.2). backfillTargets와 달리
     * SUCCESS 여부를 절대 걸러내지 않는다 — 매일 같은 대상을 다시 불러야 새로
     * 신고된 거래를 잡아내기 때문이다. 여기에 SUCCESS 필터를 넣으면 이틀째부터
     * 증분 수집이 조용히 멈춘다.
     */
    public List<IngestTarget> incrementalTargets(LocalDate today) {
        return enumerate(incrementalMonths, incrementalMonths, YearMonth.from(today));
    }

    private List<IngestTarget> enumerate(int rentMonths, int tradeMonths, YearMonth from) {
        List<IngestTarget> targets = new ArrayList<>();
        addTargets(targets, ApiType.RENT, from, rentMonths);
        addTargets(targets, ApiType.TRADE, from, tradeMonths);
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
