package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.ingest.parser.MolitFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RentDealMapperTest {

    private final ComplexResolver resolver = mock(ComplexResolver.class);
    private final RentDealMapper mapper = new RentDealMapper(resolver);

    @BeforeEach
    void setUp() {
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(7L);
    }

    private Map<String, String> item() {
        // 실측 응답(rent-ok.xml) 첫 item의 값을 기반으로 하되, contractTerm/contractType은
        // 정상(비추정) 경로를 검증하기 위해 "26.03~28.03"/"신규"로 임의로 채워 넣었다 —
        // 실측 원본은 두 필드 모두 빈 값(" ")이다.
        // 실측에서 contractTerm/useRRRight/preDeposit 은 값이 없을 때 " " 로 온다.
        Map<String, String> item = new HashMap<>();
        item.put(MolitFields.APT_SEQ, "11110-2519");
        item.put(MolitFields.SGG_CD, "11110");
        item.put(MolitFields.UMD_NM, "무악동");
        item.put(MolitFields.JIBUN, "89");
        item.put(MolitFields.APT_NM, "경희궁롯데캐슬");
        item.put(MolitFields.EXCLU_USE_AR, "59.6737");
        item.put(MolitFields.FLOOR, "11");
        item.put(MolitFields.BUILD_YEAR, "2019");
        item.put(MolitFields.DEAL_YEAR, "2026");
        item.put(MolitFields.DEAL_MONTH, "3");
        item.put(MolitFields.DEAL_DAY, "19");
        item.put(MolitFields.DEPOSIT, "71,400");
        item.put(MolitFields.MONTHLY_RENT, "0");
        item.put(MolitFields.CONTRACT_TYPE, "신규");
        item.put(MolitFields.CONTRACT_TERM, "26.03~28.03");
        item.put(MolitFields.USE_RR_RIGHT, " ");
        item.put(MolitFields.PRE_DEPOSIT, " ");
        item.put(MolitFields.PRE_MONTHLY_RENT, " ");
        return item;
    }

    @Test
    void 보증금을_원단위로_매핑한다() {
        RentDeal deal = mapper.map(item());

        // 실측: deposit "71,400" (만원) -> 7억 1,400만원
        assertThat(deal.getDeposit()).isEqualTo(714_000_000L);
    }

    @Test
    void 월세가_0이면_순수전세로_저장된다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getMonthlyRent()).isZero();
    }

    @Test
    void 반전세는_월세가_원단위로_들어간다() {
        Map<String, String> item = item();
        item.put(MolitFields.MONTHLY_RENT, "80");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getMonthlyRent()).isEqualTo(800_000L);
    }

    @Test
    void 갱신요구권_사용이면_true다() {
        Map<String, String> item = item();
        item.put(MolitFields.USE_RR_RIGHT, "사용");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getRenewalRightUsed()).isTrue();
    }

    @Test
    void 갱신요구권_값이_비면_false다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getRenewalRightUsed()).isFalse();
    }

    @Test
    void 계약기간이_정상이면_만기월을_읽고_estimated가_false다() {
        RentDeal deal = mapper.map(item());

        // 실측 형식 "26.03~28.03" -> 만기 202803
        assertThat(deal.getTermEndYm()).isEqualTo("202803");
        assertThat(deal.getTermEstimated()).isFalse();
    }

    @Test
    void 계약기간이_공백_한칸이면_계약일_더하기_24개월로_추정하고_estimated가_true다() {
        // 실측: 결측은 "" 이 아니라 " " 로 온다
        Map<String, String> item = item();
        item.put(MolitFields.CONTRACT_TERM, " ");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getTermEndYm()).isEqualTo("202803");
        assertThat(deal.getTermEstimated()).isTrue();
    }

    @Test
    void 계약기간_원본문자열을_보존한다() {
        RentDeal deal = mapper.map(item());

        // 파싱 결과가 아니라 원본을 그대로 남긴다 — 결측률 실측(Task 11)의 근거가 된다
        assertThat(deal.getContractTermRaw()).isEqualTo("26.03~28.03");
    }

    @Test
    void 종전계약금액이_비면_null이다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getPrevDeposit()).isNull();
        assertThat(deal.getPrevMonthlyRent()).isNull();
    }

    @Test
    void 종전계약보증금이_있으면_원단위로_매핑한다() {
        Map<String, String> item = item();
        item.put(MolitFields.PRE_DEPOSIT, "45,000");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getPrevDeposit()).isEqualTo(450_000_000L);
    }

    @Test
    void aptSeq를_보존한다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getAptSeq()).isEqualTo("11110-2519");
    }

    @Test
    void complexId는_aptSeq로_조회한_ComplexResolver_결과다() {
        when(resolver.resolve("11110", "11110-2519", "무악동", "89", "경희궁롯데캐슬", 2019))
                .thenReturn(42L);

        RentDeal deal = mapper.map(item());

        assertThat(deal.getComplexId()).isEqualTo(42L);
    }
}
