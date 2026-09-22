package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.ComplexRentStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ComplexRentStatRepository extends JpaRepository<ComplexRentStat, Long> {

    Optional<ComplexRentStat> findBySnapshotDateAndComplexIdAndAreaBucket(
            LocalDate snapshotDate, Long complexId, Integer areaBucket);

    List<ComplexRentStat> findBySnapshotDateAndComplexId(LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
