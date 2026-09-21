package com.jeonseradar.deal.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DealHasherTest {

    private static final String REGION = "11110";
    private static final String DONG = "청운동";
    private static final String JIBUN = "123-4";
    private static final BigDecimal AREA = new BigDecimal("84.97");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 14);

    @Test
    void 같은_입력은_같은_해시를_만든다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 해시는_64자_16진수다() {
        String hash = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 금액이_다르면_다른_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_260_000_000L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 층이_다르면_다른_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 6, DATE, 1_250_000_000L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 면적_표기가_달라도_수치가_같으면_같은_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, new BigDecimal("84.97"), 5, DATE, 1L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, new BigDecimal("84.970"), 5, DATE, 1L);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 전월세는_보증금과_월세가_모두_해시에_반영된다() {
        String jeonse = DealHasher.hashRent(REGION, DONG, JIBUN, AREA, 5, DATE, 500_000_000L, 0L);
        String banjeonse = DealHasher.hashRent(REGION, DONG, JIBUN, AREA, 5, DATE, 500_000_000L, 500_000L);

        assertThat(jeonse).isNotEqualTo(banjeonse);
    }

    /*
     * 골든 값 테스트: Task 10 전국 백필이 한 번 돌고 나면 deal_hash는 고정된 와이어 포맷이 된다.
     * 이 상수(필드 목록, 순서, 구분자, 면적 정규화 방식 중 무엇이든)를 바꾸면 이미 적재된
     * 약 1천만 건이 전부 고아 행이 되어 재적재해야 한다.
     * 그래서 여기 두 값은 "지금 나오는 해시로 갱신"하는 대상이 아니라 "포맷이 바뀌면 깨지도록
     * 일부러 박아둔 고정값"이다. 이 테스트가 실패했다면 해시 구성이 바뀐 것이고,
     * 기대값을 고쳐서 통과시키는 것이 아니라 그 변경이 의도된 것인지, 재적재가 필요한지부터
     * 확인해야 한다.
     */
    @Test
    void 골든값_hashTrade_해시_포맷이_고정되어_있다() {
        String hash = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);

        assertThat(hash).isEqualTo("16166f5bdc97addec7e176726bc776e044a473b82e6c15391d0db1d9a683b147");
    }

    @Test
    void 골든값_hashRent_해시_포맷이_고정되어_있다() {
        String hash = DealHasher.hashRent(REGION, DONG, JIBUN, AREA, 5, DATE, 500_000_000L, 500_000L);

        assertThat(hash).isEqualTo("404b36068ba594bf9d97e9d9ea0a83e3e56b7a5ee9d7e3436ccf8b172a4fa021");
    }
}
