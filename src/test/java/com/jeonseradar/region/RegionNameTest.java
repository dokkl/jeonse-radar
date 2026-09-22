package com.jeonseradar.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionNameTest {

    @Test
    void 두_토큰이면_시도와_시군구로_나뉜다() {
        RegionName n = RegionName.of("11710", "서울특별시 송파구");

        assertThat(n.sido()).isEqualTo("서울특별시");
        assertThat(n.sigungu()).isEqualTo("송파구");
        assertThat(n.sidoSegment()).isEqualTo("서울특별시");
        assertThat(n.sigunguSegment()).isEqualTo("송파구");
    }

    @Test
    void 세_토큰이면_뒤_둘을_하이픈으로_잇는다() {
        RegionName n = RegionName.of("41111", "경기도 수원시 장안구");

        assertThat(n.sido()).isEqualTo("경기도");
        assertThat(n.sigungu()).isEqualTo("수원시 장안구");
        assertThat(n.sigunguSegment()).isEqualTo("수원시-장안구");
    }

    @Test
    void 한_토큰이면_시군구가_없다() {
        RegionName n = RegionName.of("36110", "세종특별자치시");

        assertThat(n.sido()).isEqualTo("세종특별자치시");
        assertThat(n.sigungu()).isNull();
        assertThat(n.sigunguSegment()).isNull();
        assertThat(n.hasSigungu()).isFalse();
    }

    @Test
    void 통합_시도명도_그대로_쓴다() {
        RegionName n = RegionName.of("12110", "전남광주통합특별시 목포시");

        assertThat(n.sido()).isEqualTo("전남광주통합특별시");
        assertThat(n.sigungu()).isEqualTo("목포시");
    }

    @Test
    void URL_세그먼트에서_이름을_복원한다() {
        assertThat(RegionName.sigunguFromSegment("수원시-장안구")).isEqualTo("수원시 장안구");
        assertThat(RegionName.sigunguFromSegment("송파구")).isEqualTo("송파구");
    }

    @Test
    void 모르는_코드는_명확한_예외로_알린다() {
        // SigunguCatalog.nameOf()는 모르는 코드에 null을 준다. 행정구역 개편으로
        // 국토부가 새 코드를 쓰기 시작하면 실제로 일어난다 (전남광주통합특별시가 그 예).
        // NPE로 터지면 원인을 못 찾으므로 무엇이 없는지 말해주고 죽는다.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> RegionName.of("99999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99999");
    }

    @Test
    void 제목은_시군구가_있으면_시군구를_쓴다() {
        assertThat(RegionName.of("11710", "서울특별시 송파구").title())
                .isEqualTo("송파구 역전세 현황");
        assertThat(RegionName.of("36110", "세종특별자치시").title())
                .isEqualTo("세종특별자치시 역전세 현황");
    }
}
