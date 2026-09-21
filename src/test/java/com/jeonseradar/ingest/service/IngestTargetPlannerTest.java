package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.region.SigunguCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTargetPlannerTest {

    private final SigunguCatalog catalog = new SigunguCatalog();
    private final IngestTargetPlanner planner = new IngestTargetPlanner(catalog, 36, 24, 3);

    @Test
    void 증분은_직전_3개월만_대상으로_한다() {
        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        List<String> months = targets.stream()
                .map(IngestTargetPlanner.IngestTarget::dealYm)
                .distinct()
                .sorted()
                .toList();

        assertThat(months).containsExactly("202607", "202608", "202609");
    }

    @Test
    void 증분은_두_API를_모두_포함한다() {
        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        assertThat(targets).extracting(IngestTargetPlanner.IngestTarget::apiType)
                .contains(ApiType.TRADE, ApiType.RENT);
    }

    @Test
    void 증분_호출수는_시군구수_곱하기_3개월_곱하기_2API다() {
        int regions = catalog.all().size();

        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        assertThat(targets).hasSize(regions * 3 * 2);
    }

    @Test
    void 백필은_전월세36개월_매매24개월이다() {
        int regions = catalog.all().size();

        List<IngestTargetPlanner.IngestTarget> targets = planner.backfillTargets();

        long rentCount = targets.stream()
                .filter(t -> t.apiType() == ApiType.RENT).count();
        long tradeCount = targets.stream()
                .filter(t -> t.apiType() == ApiType.TRADE).count();

        assertThat(rentCount).isEqualTo((long) regions * 36);
        assertThat(tradeCount).isEqualTo((long) regions * 24);
    }
}
