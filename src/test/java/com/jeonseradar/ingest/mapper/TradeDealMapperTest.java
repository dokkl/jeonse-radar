package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.TradeDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.ingest.parser.MolitFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeDealMapperTest {

    private final ComplexResolver resolver = mock(ComplexResolver.class);
    private final TradeDealMapper mapper = new TradeDealMapper(resolver);

    @BeforeEach
    void setUp() {
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(7L);
    }

    private Map<String, String> item() {
        // 실측 응답(trade-ok.xml)의 첫 item을 그대로 옮긴 값
        Map<String, String> item = new HashMap<>();
        item.put(MolitFields.APT_SEQ, "11110-2446");
        item.put(MolitFields.SGG_CD, "11110");
        item.put(MolitFields.UMD_NM, "평동");
        item.put(MolitFields.JIBUN, "233");
        item.put(MolitFields.APT_NM, "경희궁자이(3단지)");
        item.put(MolitFields.EXCLU_USE_AR, "59.8547");
        item.put(MolitFields.FLOOR, "2");
        item.put(MolitFields.BUILD_YEAR, "2017");
        item.put(MolitFields.DEAL_YEAR, "2026");
        item.put(MolitFields.DEAL_MONTH, "3");
        item.put(MolitFields.DEAL_DAY, "7");
        item.put(MolitFields.DEAL_AMOUNT, "230,000");
        item.put(MolitFields.DEALING_GBN, "중개거래");
        item.put(MolitFields.ESTATE_AGENT_SGG_NM, "서울 종로구");
        item.put(MolitFields.RGST_DATE, "26.06.30");
        item.put(MolitFields.CDEAL_TYPE, " ");
        item.put(MolitFields.CDEAL_DAY, " ");
        return item;
    }

    @Test
    void 금액을_원단위로_매핑한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: dealAmount "230,000" (만원) -> 23억
        assertThat(deal.getDealAmount()).isEqualTo(2_300_000_000L);
    }

    @Test
    void 소수점_넷째자리_면적을_버킷으로_반올림한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: excluUseAr "59.8547" -> 버킷 60
        assertThat(deal.getAreaBucket()).isEqualTo(60);
    }

    @Test
    void 계약일을_조립한다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getDealDate()).isEqualTo(LocalDate.of(2026, 3, 7));
    }

    @Test
    void 등기일자를_yy점MM점dd_형식으로_파싱한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: rgstDate "26.06.30"
        assertThat(deal.getRegisteredDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void 등기일자가_공백_한칸이면_미등기로_남는다() {
        Map<String, String> item = item();
        item.put(MolitFields.RGST_DATE, " ");

        TradeDeal deal = mapper.map(item);

        assertThat(deal.getRegisteredDate()).isNull();
    }

    @Test
    void 해제여부가_공백_한칸이면_빈값으로_정규화된다() {
        // 실측: cdealType 은 값이 없을 때 "" 이 아니라 " " 로 온다
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getCancelYn()).isEmpty();
    }

    @Test
    void 해제여부가_O면_그대로_저장한다() {
        Map<String, String> item = item();
        item.put(MolitFields.CDEAL_TYPE, "O");
        item.put(MolitFields.CDEAL_DAY, "26.04.01");

        TradeDeal deal = mapper.map(item);

        assertThat(deal.getCancelYn()).isEqualTo("O");
        assertThat(deal.getCancelDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void aptSeq를_보존한다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getAptSeq()).isEqualTo("11110-2446");
    }

    @Test
    void 면적은_소수점_둘째자리로_저장된다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getArea()).isEqualByComparingTo(new java.math.BigDecimal("59.85"));
    }

    @Test
    void complexId는_aptSeq로_조회한_ComplexResolver_결과다() {
        when(resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이(3단지)", 2017))
                .thenReturn(42L);

        TradeDeal deal = mapper.map(item());

        assertThat(deal.getComplexId()).isEqualTo(42L);
    }
}
