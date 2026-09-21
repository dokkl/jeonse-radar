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
    void 깨진XML은_ParseException을_던진다() throws IOException {
        String xml = fixture("malformed.xml");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(MolitResponseParser.ParseException.class);
    }
}
