package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SnapshotSchedulerTest {

    private final SnapshotBuilder builder = mock(SnapshotBuilder.class);
    private final SnapshotScheduler scheduler = new SnapshotScheduler(builder);

    @Test
    void 야간_배치는_오늘_날짜로_스냅샷을_만든다() {
        when(builder.build(any())).thenReturn(mock(MetricSnapshot.class));

        scheduler.nightlyBuild();

        verify(builder, times(1)).build(LocalDate.now());
    }

    @Test
    void 빌드가_실패해도_스케줄러가_예외를_밖으로_던지지_않는다() {
        // 예외가 스케줄러 밖으로 나가면 이후 실행이 멈추는 구현이 있어 방어한다
        when(builder.build(any())).thenThrow(new RuntimeException("집계 실패"));

        scheduler.nightlyBuild();

        verify(builder, times(1)).build(any());
    }
}
