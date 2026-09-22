package com.jeonseradar.metric.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 매일 04:00 KST 집계. 수집이 02:00이므로 두 시간 뒤에 돈다 (스펙 §4.7).
 *
 * 예외를 밖으로 던지지 않는다 — 스케줄러 스레드로 예외가 빠져나가면
 * 이후 실행이 조용히 멈추는 경우가 있다. 실패는 스냅샷이 FAILED로 남고
 * 직전 COMPLETE가 계속 서비스되므로, 다음 날 다시 시도하는 것으로 충분하다.
 */
@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final SnapshotBuilder builder;

    public SnapshotScheduler(SnapshotBuilder builder) {
        this.builder = builder;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void nightlyBuild() {
        log.info("야간 집계 시작");
        try {
            builder.build(LocalDate.now());
        } catch (RuntimeException e) {
            log.error("야간 집계 실패. 직전 스냅샷이 계속 서비스된다.", e);
        }
    }
}
