package com.jeonseradar.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SigunguCatalogTest {

    private final SigunguCatalog catalog = new SigunguCatalog();

    @Test
    void 전국_시군구를_로드한다() {
        assertThat(catalog.all()).hasSizeBetween(200, 300);
    }

    @Test
    void 모든_코드는_5자리_숫자다() {
        assertThat(catalog.all()).allMatch(code -> code.matches("\\d{5}"));
    }

    @Test
    void 중복이_없다() {
        assertThat(catalog.all()).doesNotHaveDuplicates();
    }

    @Test
    void 서울_종로구가_포함된다() {
        assertThat(catalog.all()).contains("11110");
    }
}
