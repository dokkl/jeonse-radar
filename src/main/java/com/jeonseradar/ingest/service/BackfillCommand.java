package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * `--backfill` 인자로 실행할 때만 전국 백필을 수행한다. 평소에는 아무 일도 하지 않는다.
 *
 * JeonseRadarApplication은 @EnableScheduling을 갖고 있어 스케줄러 스레드 풀이
 * 데몬 스레드가 아니다. ApplicationRunner가 그냥 return하면 JVM이 종료되지
 * 않는다 — Phase 1 백필 실행마다 이미 겪은 문제이고, 매번 손으로 kill해야
 * 했다. --spring.main.web-application-type=none도 도움이 되지 않았다 (원장 F-34,
 * SnapshotCommand가 같은 문제를 이미 해결했다).
 *
 * 전국 백필은 이미 끝났다. 그래서 지금 --backfill을 다시 돌릴 이유는 무인
 * 감시 복구(결측 구간, 쿼터 재개, 신규 시군구 추가)뿐이고, 이는 정확히 오케
 * 스트레이션되지 않은 JVM이 방치되는 상황이다 — 그냥 떠 있는 게 아니라
 * @EnableScheduling 풀을 쥔 채 새벽 2시 IngestScheduler.dailyIncremental()을
 * 발사해 아무도 원치 않은 API 쿼터를 쓴다.
 *
 * 그래서 --backfill 분기에서 작업을 마치면 SpringApplication.exit로 종료
 * 코드를 계산해 System.exit으로 JVM을 직접 끝낸다. 예외는 여기서 잡아 run()
 * 밖으로 던지지 않고, 대신 0이 아닌 종료 코드로 결과를 알린다. 옵션이 없을
 * 때는 즉시 return하며 이 로직 전체를 건드리지 않는다 — 서버/스케줄러 동작은
 * 그대로다.
 *
 * 종료 처리는 테스트를 위해 분리했다: Spring이 쓰는 공개 생성자는 실제
 * System.exit로 위임하고, 패키지 전용 생성자는 종료 핸들러를 주입받는다.
 * 테스트는 그 자리에 기록만 하는 람다를 넣어 System.exit이 실제로 호출되지
 * 않게 한다.
 *
 * 생성자가 둘이라 @Autowired를 공개 생성자에 명시해야 한다 — 없으면 Spring이
 * 어느 생성자를 쓸지 정하지 못해 "No default constructor found"로 빈 생성에
 * 실패한다 (모든 @SpringBootTest가 이 컴포넌트를 인스턴스화하다가 걸린다).
 */
@Component
public class BackfillCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillCommand.class);

    private final IngestTargetPlanner planner;
    private final IngestRunner runner;
    private final ConfigurableApplicationContext context;
    private final IntConsumer exitHandler;

    @Autowired
    public BackfillCommand(IngestTargetPlanner planner, IngestRunner runner,
                           ConfigurableApplicationContext context) {
        this(planner, runner, context, System::exit);
    }

    BackfillCommand(IngestTargetPlanner planner, IngestRunner runner,
                    ConfigurableApplicationContext context, IntConsumer exitHandler) {
        this.planner = planner;
        this.runner = runner;
        this.context = context;
        this.exitHandler = exitHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            return;
        }

        int exitCode;
        try {
            var targets = planner.backfillTargets();
            int total = planner.backfillTargetCount();
            int skipped = total - targets.size();
            log.info("백필 시작: 전체 대상 {}건 중 완료분 스킵 {}건, 처리 대상 {}건",
                    total, skipped, targets.size());
            var summary = runner.run(targets);
            log.info("백필 종료: {}", summary);
            exitCode = 0;
        } catch (RuntimeException e) {
            log.error("백필 실패", e);
            exitCode = 1;
        }

        int resolvedExitCode = exitCode;
        int code = SpringApplication.exit(context, () -> resolvedExitCode);
        exitHandler.accept(code);
    }
}
