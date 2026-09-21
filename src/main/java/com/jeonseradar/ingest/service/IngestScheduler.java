package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestTargetPlanner planner;
    private final IngestRunner runner;

    public IngestScheduler(IngestTargetPlanner planner, IngestRunner runner) {
        this.planner = planner;
        this.runner = runner;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void dailyIncremental() {
        log.info("일일 증분 수집 시작");
        var summary = runner.run(planner.incrementalTargets(LocalDate.now()));
        log.info("일일 증분 수집 종료: {}", summary);
    }
}
