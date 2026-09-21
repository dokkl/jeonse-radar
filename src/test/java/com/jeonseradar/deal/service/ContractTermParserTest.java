package com.jeonseradar.deal.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTermParserTest {

    private static final LocalDate DEAL_DATE = LocalDate.of(2026, 3, 14);

    @Test
    void 실측형식_YY점MM을_파싱한다() {
        var term = ContractTermParser.parse("25.05~27.05", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202505");
        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 두자리연도는_2000년대로_해석한다() {
        var term = ContractTermParser.parse("21.11~23.11", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202111");
        assertThat(term.endYm()).isEqualTo("202311");
    }

    @Test
    void 공백이_섞여도_파싱한다() {
        var term = ContractTermParser.parse("  25.05 ~ 27.05  ", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 시작월이_없고_종료월만_있으면_종료월을_그대로_쓴다() {
        // 실측 0.4%: "~26.03"
        var term = ContractTermParser.parse("~26.03", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202603");
        assertThat(term.startYm()).isNull();
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 공백_한칸이면_추정한다() {
        // 실측: 결측은 "" 이 아니라 " " 로 온다
        var term = ContractTermParser.parse(" ", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202603");
        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void null이면_추정한다() {
        var term = ContractTermParser.parse(null, DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 형식이_깨졌으면_추정한다() {
        var term = ContractTermParser.parse("계약기간없음", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 존재하지_않는_월이면_추정으로_떨어진다() {
        var term = ContractTermParser.parse("25.05~27.13", DEAL_DATE);

        assertThat(term.estimated()).isTrue();
        assertThat(term.endYm()).isEqualTo("202803");
    }

    @Test
    void 종료가_시작보다_이르면_추정으로_떨어진다() {
        var term = ContractTermParser.parse("27.05~25.05", DEAL_DATE);

        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 구형식_YYYYMM은_실제로_오지_않지만_와도_추정으로_떨어지지_않는다() {
        // 방어적: 과거 문서에 나오는 형식. 실측 0%지만 오면 읽어준다.
        var term = ContractTermParser.parse("202505~202705", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }
}
