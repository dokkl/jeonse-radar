package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SnapshotCommand는 --build-metrics 옵션이 없으면 아무 일도 하지 않아야 한다.
 * 이 리포의 모든 @SpringBootTest가 ApplicationRunner 빈을 실행하므로, 이 가드가
 * 뒤집히거나 옵션 이름에 오타가 나면 매 컨텍스트 로드마다 집계가 돌게 된다 (F-35).
 *
 * SnapshotCommand는 작업이 끝나면 JVM을 종료한다 (F-34) — 실제 System.exit을
 * 테스트에서 호출하면 Gradle 테스트 JVM이 죽으므로, 종료 처리를 패키지 전용
 * 생성자로 주입 가능하게 만들고 여기서는 기록만 하는 람다를 넣는다.
 */
class SnapshotCommandTest {

    private final SnapshotBuilder builder = mock(SnapshotBuilder.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final AtomicBoolean exitCalled = new AtomicBoolean(false);
    private final AtomicInteger exitCode = new AtomicInteger();
    private final SnapshotCommand command = new SnapshotCommand(builder, context, code -> {
        exitCalled.set(true);
        exitCode.set(code);
    });

    @Test
    void 옵션이_없으면_빌더를_호출하지_않는다() throws Exception {
        var args = new DefaultApplicationArguments();

        command.run(args);

        verify(builder, never()).build(any());
        assertThat(exitCalled.get()).isFalse();
    }

    @Test
    void 옵션이_있으면_오늘_날짜로_빌더를_정확히_한_번_호출한다() throws Exception {
        var args = new DefaultApplicationArguments("--build-metrics");
        when(builder.build(any())).thenReturn(mock(MetricSnapshot.class));

        command.run(args);

        verify(builder, times(1)).build(LocalDate.now());
        assertThat(exitCalled.get()).isTrue();
        assertThat(exitCode.get()).isZero();
    }

    @Test
    void 빌드가_실패하면_예외가_밖으로_나가지_않고_종료코드가_0이_아니다() {
        var args = new DefaultApplicationArguments("--build-metrics");
        when(builder.build(any())).thenThrow(new RuntimeException("집계 실패"));

        assertThatCode(() -> command.run(args)).doesNotThrowAnyException();

        assertThat(exitCalled.get()).isTrue();
        assertThat(exitCode.get()).isNotZero();
    }
}
