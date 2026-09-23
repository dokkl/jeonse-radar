package com.jeonseradar.ingest.repository;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngestLogRepository extends JpaRepository<IngestLog, Long> {

    Optional<IngestLog> findByApiTypeAndRegionCodeAndDealYm(
            ApiType apiType, String regionCode, String dealYm);

    List<IngestLog> findByStatus(IngestStatus status);

    long countByStatus(IngestStatus status);

    /**
     * 백필 재개 대상을 고를 때 쓴다. IngestLog 전체가 아니라 (apiType, regionCode, dealYm)
     * 세 컬럼만 프로젝션해, 대상 16,140건을 건별로 조회하지 않고 SUCCESS 전체를 한 번에
     * 가져와 메모리에서 걸러낸다 (결함 수정: IngestTargetPlanner#backfillTargets 참고).
     */
    List<SuccessKey> findKeysByStatus(IngestStatus status);

    interface SuccessKey {
        ApiType getApiType();
        String getRegionCode();
        String getDealYm();
    }
}
