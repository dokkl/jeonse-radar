package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.entity.SnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    Optional<MetricSnapshot> findBySnapshotDate(LocalDate snapshotDate);

    /**
     * 조회 계층이 쓰는 단 하나의 진입점.
     * BUILDING이나 FAILED는 더 최신이어도 무시된다 — 반쪽짜리 집계가 화면에 새지 않게 한다.
     *
     * JPQL의 LIMIT은 Hibernate 버전에 따라 지원이 갈리므로 파생 쿼리로 쓴다.
     */
    default Optional<MetricSnapshot> findLatestComplete() {
        return findFirstByStatusOrderBySnapshotDateDesc(SnapshotStatus.COMPLETE);
    }

    Optional<MetricSnapshot> findFirstByStatusOrderBySnapshotDateDesc(SnapshotStatus status);

    long countByStatus(SnapshotStatus status);
}
