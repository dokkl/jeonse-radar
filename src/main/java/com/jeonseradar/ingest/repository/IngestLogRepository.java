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
}
