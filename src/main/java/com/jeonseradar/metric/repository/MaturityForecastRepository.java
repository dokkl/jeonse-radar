package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.MaturityForecast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MaturityForecastRepository extends JpaRepository<MaturityForecast, Long> {

    List<MaturityForecast> findBySnapshotDateAndComplexIdOrderByMaturityYm(
            LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
