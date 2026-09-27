package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.RenewalReductionStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RenewalReductionStatRepository extends JpaRepository<RenewalReductionStat, Long> {

    Optional<RenewalReductionStat> findBySnapshotDateAndComplexIdAndAreaBucket(
            LocalDate snapshotDate, Long complexId, Integer areaBucket);

    List<RenewalReductionStat> findBySnapshotDateAndComplexId(LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
