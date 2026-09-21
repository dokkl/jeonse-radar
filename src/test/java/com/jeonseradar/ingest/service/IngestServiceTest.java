package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.MolitResponseParser;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void 인증키오류는_재시도하지_않는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.INVALID_KEY, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(InvalidKeyException.class);

        verify(client, times(1)).fetch(any(), anyString(), anyString());
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

    @Test
    void RENT_정상응답은_매퍼를_거쳐_upsert된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.OK, 3));

        service.ingestOne(ApiType.RENT, "11110", "202603");

        verify(rentMapper, times(3)).map(any());
        verify(upsertDao, times(1)).upsertRents(any());
    }

    // ingest_log는 다음 날 실행이 "무엇이 아직 안 됐는지" 판단하는 유일한 근거다.
    // save()가 호출되지 않거나 잘못된 status로 저장되면 재시도 큐가 조용히 고장난다 —
    // 아래 다섯 테스트는 각 결과 경로에서 save()가 호출되고, 기록된 status가
    // 실제 결과와 일치하는지를 직접 확인한다.

    @Test
    void SUCCESS_경로에서_ingest_log가_저장된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.OK, 2));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        ArgumentCaptor<IngestLog> captor = ArgumentCaptor.forClass(IngestLog.class);
        verify(logRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(captor.getValue().getRowCount()).isEqualTo(2);
    }

    @Test
    void NODATA_경로에서_ingest_log가_SUCCESS로_저장된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.NODATA, 0));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        ArgumentCaptor<IngestLog> captor = ArgumentCaptor.forClass(IngestLog.class);
        verify(logRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(captor.getValue().getRowCount()).isZero();
    }

    @Test
    void 일시오류_경로에서_ingest_log가_FAILED로_저장된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.ERROR, 0));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        ArgumentCaptor<IngestLog> captor = ArgumentCaptor.forClass(IngestLog.class);
        verify(logRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(captor.getValue().getRowCount()).isZero();
    }

    @Test
    void 쿼터초과_경로에서_ingest_log가_PENDING으로_저장된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.QUOTA_EXCEEDED, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(QuotaExceededException.class);

        ArgumentCaptor<IngestLog> captor = ArgumentCaptor.forClass(IngestLog.class);
        verify(logRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestStatus.PENDING);
    }

    @Test
    void 인증키오류_경로에서_ingest_log가_FAILED로_저장된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.INVALID_KEY, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(InvalidKeyException.class);

        ArgumentCaptor<IngestLog> captor = ArgumentCaptor.forClass(IngestLog.class);
        verify(logRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestStatus.FAILED);
    }
}
