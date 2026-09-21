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
}
