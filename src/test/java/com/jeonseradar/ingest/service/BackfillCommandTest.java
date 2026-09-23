package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * BackfillCommand는 --backfill 옵션이 없으면 아무 일도 하지 않아야 한다.
 * 이 리포의 모든 @SpringBootTest가 ApplicationRunner 빈을 실행하므로, 이 가드가
 * 뒤집히거나 옵션 이름에 오타가 나면 매 컨텍스트 로드마다 백필이 돌게 된다.
 *
 * BackfillCommand는 작업이 끝나면 JVM을 종료한다 (F-34, SnapshotCommand와 동일한
 * 문제) — 실제 System.exit을 테스트에서 호출하면 Gradle 테스트 JVM이 죽으므로,
 * 종료 처리를 패키지 전용 생성자로 주입 가능하게 만들고 여기서는 기록만 하는
 * 람다를 넣는다.
 */
class BackfillCommandTest {

    private final IngestTargetPlanner planner = mock(IngestTargetPlanner.class);
    private final IngestRunner runner = mock(IngestRunner.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final AtomicBoolean exitCalled = new AtomicBoolean(false);
    private final AtomicInteger exitCode = new AtomicInteger();
    private final BackfillCommand command = new BackfillCommand(planner, runner, context, code -> {
        exitCalled.set(true);
        exitCode.set(code);
    });

    @Test
    void 옵션이_없으면_플래너와_러너를_호출하지_않는다() throws Exception {
        var args = new DefaultApplicationArguments();

        command.run(args);

        verify(planner, never()).backfillTargets();
        verify(runner, never()).run(anyList());
        assertThat(exitCalled.get()).isFalse();
    }

    @Test
    void 옵션이_있으면_백필을_정확히_한_번_실행하고_종료코드가_0이다() throws Exception {
        var args = new DefaultApplicationArguments("--backfill");
        List<IngestTargetPlanner.IngestTarget> targets =
                List.of(new IngestTargetPlanner.IngestTarget(ApiType.RENT, "11110", "202603"));
        when(planner.backfillTargets()).thenReturn(targets);
        when(planner.backfillTargetCount()).thenReturn(1);
        when(runner.run(targets)).thenReturn(new IngestRunner.RunSummary(1, 0, 0, false));

        command.run(args);

        verify(runner, times(1)).run(targets);
        assertThat(exitCalled.get()).isTrue();
        assertThat(exitCode.get()).isZero();
    }

    @Test
    void 백필이_실패하면_예외가_밖으로_나가지_않고_종료코드가_0이_아니다() {
        var args = new DefaultApplicationArguments("--backfill");
        when(planner.backfillTargets()).thenThrow(new RuntimeException("백필 실패"));

        assertThatCode(() -> command.run(args)).doesNotThrowAnyException();

        assertThat(exitCalled.get()).isTrue();
        assertThat(exitCode.get()).isNotZero();
    }
}
