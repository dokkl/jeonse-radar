package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IngestRunnerTest {

    private final IngestService service = mock(IngestService.class);
    private final IngestRunner runner = new IngestRunner(service);

    private List<IngestTargetPlanner.IngestTarget> targets(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new IngestTargetPlanner.IngestTarget(
                        ApiType.TRADE, String.format("%05d", 11110 + i), "202609"))
                .toList();
    }

    @Test
    void 쿼터초과를_만나면_남은_대상을_호출하지_않는다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 1, "00"))
                .thenThrow(new QuotaExceededException("초과"));

        var summary = runner.run(targets(10));

        assertThat(summary.abortedByQuota()).isTrue();
        assertThat(summary.success()).isEqualTo(1);
        verify(service, times(2)).ingestOne(any(), anyString(), anyString());
    }

    @Test
    void 개별_실패는_전체를_중단시키지_않는다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenReturn(new IngestService.IngestResult(IngestStatus.FAILED, 0, "99"))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 5, "00"))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 3, "00"));

        var summary = runner.run(targets(3));

        assertThat(summary.abortedByQuota()).isFalse();
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.success()).isEqualTo(2);
    }

    @Test
    void 인증키오류도_배치를_중단시킨다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenThrow(new InvalidKeyException("키 오류"));

        var summary = runner.run(targets(5));

        assertThat(summary.success()).isZero();
        verify(service, times(1)).ingestOne(any(), anyString(), anyString());
    }
}
