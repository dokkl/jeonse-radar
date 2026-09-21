package com.jeonseradar.ingest.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ParsedResponse#hasMorePages(int, int)}의 경계 산술(pageNo * rowsPerPage < totalCount)을
 * 검증한다. Task 8의 MolitApiClient가 이 메서드로 페이지네이션을 진행하며, 클라이언트 자체는
 * 단위 테스트 대상이 아니므로 이 경계 로직은 여기서만 검증된다. off-by-one이 나면 강남구처럼
 * 건수가 많은 시군구가 뒤쪽 페이지를 조용히 잃어버리고도 배치는 성공으로 보고된다.
 */
class ParsedResponseTest {

    private ParsedResponse withTotal(int totalCount) {
        return new ParsedResponse(ResultCode.OK, "000", totalCount, List.<Map<String, String>>of());
    }

    @Test
    void 정확히_한_페이지분량이면_더_이상_페이지가_없다() {
        // rowsPerPage=10, totalCount=10: 1페이지가 전부를 정확히 담는다.
        assertThat(withTotal(10).hasMorePages(1, 10)).isFalse();
    }

    @Test
    void 페이지_경계보다_한_건_적으면_더_이상_페이지가_없다() {
        // rowsPerPage=10, totalCount=9: 경계(10)보다 한 건 적다.
        assertThat(withTotal(9).hasMorePages(1, 10)).isFalse();
    }

    @Test
    void 페이지_경계보다_한_건_많으면_다음_페이지가_있다() {
        // rowsPerPage=10, totalCount=11: 경계(10)보다 한 건 많아 2페이지가 필요하다.
        assertThat(withTotal(11).hasMorePages(1, 10)).isTrue();
    }

    @Test
    void 결과가_0건이면_더_이상_페이지가_없다() {
        assertThat(withTotal(0).hasMorePages(1, 10)).isFalse();
    }

    @Test
    void 총건수가_페이지크기의_정확한_배수이면_더_이상_페이지가_없다() {
        // 전형적인 off-by-one 지점: pageNo * rowsPerPage == totalCount일 때 <(초과아님)로
        // 판정해야 한다. <=였다면 여기서 존재하지 않는 다음 페이지를 요청하게 된다.
        assertThat(withTotal(20).hasMorePages(2, 10)).isFalse();
    }
}
