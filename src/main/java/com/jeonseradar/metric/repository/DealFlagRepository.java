package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.DealFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DealFlagRepository extends JpaRepository<DealFlag, Long> {

    List<DealFlag> findBySnapshotDateAndComplexId(LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
