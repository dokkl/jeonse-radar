package com.jeonseradar.deal.service;

import java.text.Normalizer;
import java.util.Locale;

public final class SlugGenerator {

    private SlugGenerator() {
    }

    /**
     * URL 경로용 slug. 한글은 유지하고(검색엔진이 처리 가능) 공백·특수문자만 정리한다.
     * aptSeq를 뒤에 붙여 같은 이름의 다른 단지를 구분한다.
     */
    public static String generate(String aptName, String aptSeq) {
        String base = Normalizer.normalize(aptName == null ? "" : aptName, Normalizer.Form.NFC)
                .toLowerCase(Locale.KOREAN)
                .replaceAll("[^0-9a-z가-힣]+", "-")
                .replaceAll("(^-|-$)", "");
        String suffix = (aptSeq == null ? "" : aptSeq)
                .replaceAll("[^0-9a-z가-힣-]+", "-")
                .replaceAll("(^-|-$)", "");
        String slug = base.isEmpty() ? suffix : base + "-" + suffix;
        return slug.length() > 150 ? slug.substring(0, 150) : slug;
    }
}
