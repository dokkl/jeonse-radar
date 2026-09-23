package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** `--backfill` 인자로 실행할 때만 전국 백필을 수행한다. 평소에는 아무 일도 하지 않는다. */
@Component
public class BackfillCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillCommand.class);

    private final IngestTargetPlanner planner;
    private final IngestRunner runner;

    public BackfillCommand(IngestTargetPlanner planner, IngestRunner runner) {
        this.planner = planner;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            return;
        }
        var targets = planner.backfillTargets();
        int total = planner.backfillTargetCount();
        int skipped = total - targets.size();
        log.info("백필 시작: 전체 대상 {}건 중 완료분 스킵 {}건, 처리 대상 {}건",
                total, skipped, targets.size());
        var summary = runner.run(targets);
        log.info("백필 종료: {}", summary);
    }
}
