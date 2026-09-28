package com.jeonseradar.web.root;

import java.time.LocalDate;
import java.util.List;

/**
 * 루트 페이지가 보여주는 것 전부.
 *
 * <p>순위나 지표를 담지 않는다. 이 페이지의 일은 크롤러와 사람에게 시도로 내려가는
 * 길을 열어 주는 것이고, 숫자는 그 데이터가 얼마나 넓은지를 한 줄로 말해 줄 뿐이다.
 *
 * @param snapshotDate 발행된 스냅샷 날짜
 * @param sigunguCount 단지 데이터가 있는 시군구 수
 * @param complexCount 수집된 단지 수
 * @param sidos        단지 데이터가 있는 시도 이름, 행정구역 코드 순
 */
public record RootPage(LocalDate snapshotDate, int sigunguCount, long complexCount, List<String> sidos) {
}
