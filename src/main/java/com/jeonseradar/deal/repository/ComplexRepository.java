package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.Complex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ComplexRepository extends JpaRepository<Complex, Long> {

    Optional<Complex> findByRegionCodeAndAptSeq(String regionCode, String aptSeq);

    boolean existsBySlug(String slug);
}
