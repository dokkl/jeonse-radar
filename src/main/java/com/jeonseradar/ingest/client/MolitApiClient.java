package com.jeonseradar.ingest.client;

import com.jeonseradar.ingest.parser.MolitResponseParser;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MolitApiClient {

    private final RestClient restClient;
    private final MolitResponseParser parser;
    private final MolitProperties properties;

    public MolitApiClient(RestClient restClient, MolitResponseParser parser,
                          MolitProperties properties) {
        this.restClient = restClient;
        this.parser = parser;
        this.properties = properties;
    }

    /**
     * (시군구, 계약년월) 한 쌍의 전 페이지를 받아 하나의 ParsedResponse로 합친다.
     * OK가 아닌 코드를 만나면 즉시 그 응답을 그대로 반환한다 — 판정은 호출자 책임이다.
     */
    public ParsedResponse fetch(ApiType apiType, String lawdCd, String dealYmd) {
        List<Map<String, String>> all = new ArrayList<>();
        int pageNo = 1;
        ParsedResponse page;

        do {
            page = fetchPage(apiType, lawdCd, dealYmd, pageNo);
            if (page.code() != ResultCode.OK) {
                return page;
            }
            all.addAll(page.items());
            pageNo++;
            sleep();
        } while (page.hasMorePages(pageNo - 1, properties.rowsPerPage()));

        return new ParsedResponse(ResultCode.OK, page.rawCode(), page.totalCount(), all);
    }

    private ParsedResponse fetchPage(ApiType apiType, String lawdCd, String dealYmd, int pageNo) {
        String uri = UriComponentsBuilder
                .fromUriString(properties.baseUrl())
                .pathSegment(apiType.path().split("/"))
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("LAWD_CD", lawdCd)
                .queryParam("DEAL_YMD", dealYmd)
                .queryParam("numOfRows", properties.rowsPerPage())
                .queryParam("pageNo", pageNo)
                .build(true)
                .toUriString();

        // 공공 API는 인증 오류·쿼터 초과를 HTTP 403 등 4xx로 반환하면서 본문에
        // <cmmMsgHeader><returnReasonCode>로 진짜 원인을 담아 보낸다 (2026-09-21 실측).
        // retrieve()의 기본 동작은 4xx/5xx에서 예외를 던지는 것이라, 그대로 두면 본문이
        // 파서에 도달하지 못하고 INVALID_KEY/QUOTA_EXCEEDED가 일반 예외로 뭉개져
        // "즉시 중단" 대신 재시도로 빠진다 — 스펙 §1.6 위반.
        // 따라서 상태 코드로는 절대 실패시키지 않고, 언제나 본문의 resultCode로 판정한다.
        String body = restClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .body(String.class);
        return parser.parse(body == null ? "" : body);
    }

    private void sleep() {
        if (properties.delayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
