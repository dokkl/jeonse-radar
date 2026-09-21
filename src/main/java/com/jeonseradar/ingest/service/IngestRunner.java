package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.entity.IngestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    public record RunSummary(int success, int failed, int skipped, boolean abortedByQuota) {
    }

    private final IngestService ingestService;

    public IngestRunner(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    public RunSummary run(List<IngestTargetPlanner.IngestTarget> targets) {
        int success = 0;
        int failed = 0;

        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            try {
                var result = ingestService.ingestOne(
                        target.apiType(), target.regionCode(), target.dealYm());
                if (result.status() == IngestStatus.SUCCESS) {
                    success++;
                } else {
                    failed++;
                }
            } catch (QuotaExceededException e) {
                log.warn("일일 쿼터 소진으로 배치 중단. 처리 {}/{}, 남은 대상은 내일 재개",
                        i, targets.size());
                return new RunSummary(success, failed, targets.size() - i, true);
            } catch (InvalidKeyException e) {
                log.error("서비스키 오류로 배치 중단: {}", e.getMessage());
                return new RunSummary(success, failed, targets.size() - i, false);
            }
        }

        log.info("수집 완료: 성공 {}, 실패 {}", success, failed);
        return new RunSummary(success, failed, 0, false);
    }
}
