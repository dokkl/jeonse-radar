package com.jeonseradar.ingest.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldConverterTest {

    @Test
    void 만원단위_콤마_공백_금액을_원단위로_변환한다() {
        assertThat(FieldConverter.toWon("  125,000")).isEqualTo(1_250_000_000L);
        assertThat(FieldConverter.toWon("52,000")).isEqualTo(520_000_000L);
        assertThat(FieldConverter.toWon("0")).isZero();
    }

    @Test
    void 빈금액은_0으로_변환한다() {
        assertThat(FieldConverter.toWon("")).isZero();
        assertThat(FieldConverter.toWon(null)).isZero();
        assertThat(FieldConverter.toWon("   ")).isZero();
    }

    @Test
    void 전용면적은_소수점_둘째자리까지_보존한다() {
        assertThat(FieldConverter.toArea("84.97")).isEqualByComparingTo(new BigDecimal("84.97"));
        assertThat(FieldConverter.toArea(" 59.9 ")).isEqualByComparingTo(new BigDecimal("59.90"));
    }

    @Test
    void 실제응답의_소수점_넷째자리_면적을_둘째자리로_반올림한다() {
        // 실측: excluUseAr = "59.8547" 처럼 4자리로 온다
        assertThat(FieldConverter.toArea("59.8547")).isEqualByComparingTo(new BigDecimal("59.85"));
        assertThat(FieldConverter.toArea("79.87")).isEqualByComparingTo(new BigDecimal("79.87"));
    }

    @Test
    void 공백_한칸으로_오는_값은_비어있는_것으로_다룬다() {
        // 실측: cdealType, cdealDay, preDeposit 등이 "" 가 아니라 " " 로 온다
        assertThat(FieldConverter.toWon(" ")).isZero();
        assertThat(FieldConverter.toRegisteredDate(" ")).isNull();
    }

    @Test
    void 면적버킷은_1제곱미터_단위_반올림이다() {
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.97"))).isEqualTo(85);
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.49"))).isEqualTo(84);
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.50"))).isEqualTo(85);
    }

    @Test
    void 같은_평형의_미세한_면적차이는_같은_버킷으로_묶인다() {
        int a = FieldConverter.toAreaBucket(new BigDecimal("84.97"));
        int b = FieldConverter.toAreaBucket(new BigDecimal("84.90"));
        int c = FieldConverter.toAreaBucket(new BigDecimal("85.00"));

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void 지번은_jibun필드를_우선_사용한다() {
        Map<String, String> item = Map.of(MolitFields.JIBUN, "123-4");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123-4");
    }

    @Test
    void jibun이_없으면_본번과_부번을_조합한다() {
        Map<String, String> item = Map.of(
                MolitFields.BONBUN, "0123",
                MolitFields.BUBUN, "0004");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123-4");
    }

    @Test
    void 부번이_0이면_본번만_사용한다() {
        Map<String, String> item = Map.of(
                MolitFields.BONBUN, "0123",
                MolitFields.BUBUN, "0000");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123");
    }

    @Test
    void 계약일은_연월일_필드를_조립한다() {
        Map<String, String> item = Map.of(
                MolitFields.DEAL_YEAR, "2026",
                MolitFields.DEAL_MONTH, "3",
                MolitFields.DEAL_DAY, "14");

        assertThat(FieldConverter.toDealDate(item)).isEqualTo(LocalDate.of(2026, 3, 14));
    }

    @Test
    void 등기일자가_비어있으면_null이다() {
        assertThat(FieldConverter.toRegisteredDate("")).isNull();
        assertThat(FieldConverter.toRegisteredDate(null)).isNull();
        assertThat(FieldConverter.toRegisteredDate("  ")).isNull();
    }

    @Test
    void 등기일자는_yy_MM_dd_형식을_파싱한다() {
        assertThat(FieldConverter.toRegisteredDate("26.05.20"))
                .isEqualTo(LocalDate.of(2026, 5, 20));
    }
}
