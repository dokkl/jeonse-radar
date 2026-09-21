package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.TradeDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeDealRepository extends JpaRepository<TradeDeal, Long> {
    Optional<TradeDeal> findByDealHash(String dealHash);
}
