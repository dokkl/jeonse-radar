package com.jeonseradar.ingest.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MolitResponseParserTest {

    private final MolitResponseParser parser = new MolitResponseParser();

    private String fixture(String name) throws IOException {
        try (var in = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/molit/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 정상응답은_OK와_항목들을_반환한다() throws IOException {
        ParsedResponse result = parser.parse(fixture("trade-ok.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.OK);
        assertThat(result.items()).isNotEmpty();
        assertThat(result.totalCount()).isPositive();
        assertThat(result.items().getFirst()).containsKeys("aptNm", "aptSeq", "sggCd");
    }

    @Test
    void NODATA는_OK가_아니라_NODATA로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("nodata.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.NODATA);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
    }

    @Test
    void 쿼터초과는_cmmMsgHeader에서_읽어_QUOTA_EXCEEDED로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("quota-exceeded.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.QUOTA_EXCEEDED);
    }

    @Test
    void 인증키오류는_INVALID_KEY로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("invalid-key.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.INVALID_KEY);
    }

    @Test
    void 코드는_앞자리_0을_무시하고_정규화된다() {
        assertThat(ResultCode.from("000")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("00")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("0")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("003")).isEqualTo(ResultCode.NODATA);
        assertThat(ResultCode.from("03")).isEqualTo(ResultCode.NODATA);
    }

    @Test
    void 알수없는코드와_null_blank는_ERROR로_판정된다() {
        // ERROR는 배치가 abort 대신 retry하도록 만드는 분류다. OK나 QUOTA_EXCEEDED로
        // 잘못 떨어지면 이 테스트가 없는 한 아무도 잡지 못한다.
        assertThat(ResultCode.from("99")).isEqualTo(ResultCode.ERROR);
        assertThat(ResultCode.from(null)).isEqualTo(ResultCode.ERROR);
        assertThat(ResultCode.from("   ")).isEqualTo(ResultCode.ERROR);
    }

    @Test
    void 깨진XML은_ParseException을_던진다() throws IOException {
        String xml = fixture("malformed.xml");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(MolitResponseParser.ParseException.class);
    }
}
