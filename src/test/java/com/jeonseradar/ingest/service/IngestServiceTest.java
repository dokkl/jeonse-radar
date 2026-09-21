package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.MolitResponseParser;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    MolitApiClient client;
    TradeDealMapper tradeMapper;
    RentDealMapper rentMapper;
    DealUpsertDao upsertDao;
    IngestLogRepository logRepository;
    IngestService service;

    @BeforeEach
    void setUp() {
        client = mock(MolitApiClient.class);
        tradeMapper = mock(TradeDealMapper.class);
        rentMapper = mock(RentDealMapper.class);
        upsertDao = mock(DealUpsertDao.class);
        logRepository = mock(IngestLogRepository.class);
        when(logRepository.findByApiTypeAndRegionCodeAndDealYm(any(), anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        service = new IngestService(client, tradeMapper, rentMapper, upsertDao, logRepository, 3);
    }

    private ParsedResponse response(ResultCode code, int itemCount) {
        List<Map<String, String>> items = new java.util.ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(Map.of("aptNm", "단지" + i));
        }
        return new ParsedResponse(code, "x", itemCount, items);
    }

    @Test
    void NODATA는_실패가_아니라_0건_성공이다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.NODATA, 0));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void NODATA는_재시도하지_않는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.NODATA, 0));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        verify(client, times(1)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 쿼터초과는_예외를_던져_배치를_중단시킨다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.QUOTA_EXCEEDED, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void 쿼터초과는_재시도하지_않는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.QUOTA_EXCEEDED, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(QuotaExceededException.class);

        verify(client, times(1)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 인증키오류는_예외를_던진다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.INVALID_KEY, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(InvalidKeyException.class);
    }

    @Test
    void 일시오류는_최대_3회_재시도한_뒤_FAILED로_남는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.ERROR, 0));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.FAILED);
        verify(client, times(3)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 파싱실패도_재시도_대상이다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenThrow(new MolitResponseParser.ParseException("깨짐", new RuntimeException()));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.FAILED);
        verify(client, times(3)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 재시도_도중_성공하면_SUCCESS다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.ERROR, 0))
                .thenReturn(response(ResultCode.OK, 2));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(result.rowCount()).isEqualTo(2);
        verify(client, times(2)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 정상응답은_매퍼를_거쳐_upsert된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.OK, 3));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        verify(tradeMapper, times(3)).map(any());
        verify(upsertDao, times(1)).upsertTrades(any());
    }
}
