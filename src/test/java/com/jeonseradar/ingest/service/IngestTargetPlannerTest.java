package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import com.jeonseradar.region.SigunguCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IngestTargetPlannerTest {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 실제 269개 시군구 대신 4개 코드만 노출하는 카탈로그. backfillTargets()가 만드는
     * 대상 전체를 테스트에서 손으로 나열할 수 있게 해, "SUCCESS 하나만 빠지고 나머지는
     * 그대로 남는다"를 정확히 단정하기 위함이다.
     */
    private static class FixedCatalog extends SigunguCatalog {
        @Override
        public List<String> all() {
            return List.of("11110", "11140", "11170", "11200");
        }
    }

    private final SigunguCatalog catalog = new SigunguCatalog();
    private final IngestLogRepository logRepository = mock(IngestLogRepository.class);
    private final IngestTargetPlanner planner =
            new IngestTargetPlanner(catalog, logRepository, 36, 24, 3);

    @BeforeEach
    void setUp() {
        // 실측 결함 재현: 아무것도 SUCCESS로 기록되지 않은 상태에서는 필터링 전과
        // 동일하게 전체가 그대로 나와야 한다 (기존 4개 테스트가 이 가정 위에 서 있다).
        when(logRepository.findKeysByStatus(IngestStatus.SUCCESS)).thenReturn(List.of());
    }

    private IngestLogRepository.SuccessKey successKey(ApiType apiType, String regionCode, String dealYm) {
        IngestLogRepository.SuccessKey key = mock(IngestLogRepository.SuccessKey.class);
        when(key.getApiType()).thenReturn(apiType);
        when(key.getRegionCode()).thenReturn(regionCode);
        when(key.getDealYm()).thenReturn(dealYm);
        return key;
    }

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

    @Test
    void 백필_대상_건수는_필터링_전_전체_나열_크기와_같다() {
        // backfillTargetCount()는 DB 조회 없이 catalog.all().size() * (rent+trade)로
        // 계산해, BackfillCommand가 "스킵 N건 / 처리 M건" 로그의 모수(N+M)로 쓴다. 이
        // 값이 enumerate()가 실제로 만드는 전체 나열 크기와 어긋나면 아무것도 깨지지
        // 않은 채 운영 로그만 조용히 잘못된 숫자를 찍는다 — 원래 결함이 들키지 않았던
        // 방식 그대로다. setUp()에서 findKeysByStatus(SUCCESS)를 빈 목록으로 세팅해
        // 두었으므로, 여기서 backfillTargets()는 필터링 전 전체 나열과 동일하다.
        List<IngestTargetPlanner.IngestTarget> allTargets = planner.backfillTargets();

        assertThat(planner.backfillTargetCount()).isEqualTo(allTargets.size());
    }

    // 아래부터는 "재실행하면 이미 SUCCESS인 대상까지 전부 다시 긁는다" 결함에 대한
    // 회귀 테스트다 (2026-09-23 오전 실측: 대상 16140건을 그대로 다시 돌려 9분 만에
    // 이미 SUCCESS였던 290건을 재수집하고 킬됨). PENDING/FAILED/미기록은 계속
    // 재시도 대상이어야 하므로 셋 다 살아있는지도 함께 확인한다.

    @Test
    void 백필_대상에서_SUCCESS는_빠지고_나머지는_그대로_남는다() {
        String currentYm = YearMonth.now().format(YM);
        IngestLogRepository repo = mock(IngestLogRepository.class);
        // ingest_log 시딩: 11110=SUCCESS, 11140=FAILED, 11170=PENDING, 11200=미기록.
        // findKeysByStatus(SUCCESS)는 DB에서 status=SUCCESS인 행만 걸러 돌려주므로,
        // 이 목은 11110 하나만 돌려주는 것으로 그 시딩을 그대로 나타낸다.
        IngestLogRepository.SuccessKey successKey = successKey(ApiType.RENT, "11110", currentYm);
        when(repo.findKeysByStatus(IngestStatus.SUCCESS))
                .thenReturn(List.of(successKey));

        IngestTargetPlanner localPlanner = new IngestTargetPlanner(new FixedCatalog(), repo, 1, 1, 3);

        List<IngestTargetPlanner.IngestTarget> targets = localPlanner.backfillTargets();

        assertThat(targets)
                .doesNotContain(new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11110", currentYm))
                .contains(
                        new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11140", currentYm), // FAILED
                        new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11170", currentYm), // PENDING
                        new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11200", currentYm), // 미기록
                        new IngestTargetPlanner.IngestTarget(ApiType.TRADE, "11110", currentYm)); // 다른 API는 그대로
    }

    @Test
    void SUCCESS_대상은_백필_실행중_API를_호출하지_않는다() {
        String currentYm = YearMonth.now().format(YM);
        IngestLogRepository repo = mock(IngestLogRepository.class);
        IngestLogRepository.SuccessKey successKey = successKey(ApiType.RENT, "11110", currentYm);
        when(repo.findKeysByStatus(IngestStatus.SUCCESS))
                .thenReturn(List.of(successKey));
        when(repo.findByApiTypeAndRegionCodeAndDealYm(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        IngestTargetPlanner localPlanner = new IngestTargetPlanner(new FixedCatalog(), repo, 1, 1, 3);

        MolitApiClient client = mock(MolitApiClient.class);
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(new ParsedResponse(ResultCode.NODATA, "00", 0, List.<Map<String, String>>of()));
        TradeDealMapper tradeMapper = mock(TradeDealMapper.class);
        RentDealMapper rentMapper = mock(RentDealMapper.class);
        DealUpsertDao upsertDao = mock(DealUpsertDao.class);
        IngestService service = new IngestService(client, tradeMapper, rentMapper, upsertDao, repo, 3);
        IngestRunner runner = new IngestRunner(service);

        runner.run(localPlanner.backfillTargets());

        verify(client, never()).fetch(ApiType.RENT, "11110", currentYm);
        verify(client, times(1)).fetch(ApiType.RENT, "11140", currentYm);
        verify(client, times(1)).fetch(ApiType.RENT, "11170", currentYm);
        verify(client, times(1)).fetch(ApiType.RENT, "11200", currentYm);
        verify(client, times(1)).fetch(ApiType.TRADE, "11110", currentYm);
        verify(client, times(1)).fetch(ApiType.TRADE, "11140", currentYm);
        verify(client, times(1)).fetch(ApiType.TRADE, "11170", currentYm);
        verify(client, times(1)).fetch(ApiType.TRADE, "11200", currentYm);
    }

    @Test
    void 증분_대상은_이미_SUCCESS인_것도_그대로_돌려준다() {
        // IngestScheduler.dailyIncremental()가 매일 재수집해야 하는 이유는 신규 신고분을
        // 잡아내기 위함이다 — incrementalTargets가 SUCCESS를 걸러내기 시작하면
        // 이틀째부터 증분 수집이 조용히 멈춘다. backfillTargets와는 정반대 요구사항이므로
        // 같은 로그를 봐도 결과가 달라야 한다.
        IngestLogRepository repo = mock(IngestLogRepository.class);
        IngestLogRepository.SuccessKey successKey = successKey(ApiType.RENT, "11110", "202609");
        when(repo.findKeysByStatus(IngestStatus.SUCCESS))
                .thenReturn(List.of(successKey));

        IngestTargetPlanner localPlanner = new IngestTargetPlanner(new FixedCatalog(), repo, 36, 24, 3);

        List<IngestTargetPlanner.IngestTarget> targets =
                localPlanner.incrementalTargets(LocalDate.of(2026, 9, 21));

        assertThat(targets)
                .contains(new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11110", "202609"));
    }
}
