package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.RentDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RentDealRepository extends JpaRepository<RentDeal, Long> {
    Optional<RentDeal> findByDealHash(String dealHash);
}
