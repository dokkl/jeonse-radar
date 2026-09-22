package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 매매 거래 검증 플래그를 집계한다 (스펙 §3.7).
 *
 * 사실만 담는다. "자전거래 의심" 같은 판단은 만들지 않으며, 신뢰도 점수도 매기지 않는다.
 * 화면에는 "신고 후 해제된 거래", "등기 미확인(계약 후 191일 경과)"처럼 사실로만 표기한다.
 */
@Service
public class DealFlagBuilder {

    /** 잔금과 등기에 걸리는 기간. 이보다 최근 거래에 등기가 없는 것은 정상이다. */
    private static final int REGISTRATION_GRACE_DAYS = 60;

    private final JdbcTemplate jdbcTemplate;

    public DealFlagBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int build(LocalDate snapshotDate, LocalDate asOf) {
        int n = insertCanceled(snapshotDate);
        n += insertUnregistered(snapshotDate, asOf);
        n += insertDirect(snapshotDate);
        return n;
    }

    private int insertCanceled(LocalDate snapshotDate) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'CANCELED', NOW()
                FROM trade_deal WHERE cancel_yn = 'O'
                """, snapshotDate);
    }

    /**
     * 해제된 거래는 제외한다 — 해제됐으면 등기가 없는 것이 당연하므로
     * 같은 사실에 두 번 낙인을 찍지 않는다.
     */
    private int insertUnregistered(LocalDate snapshotDate, LocalDate asOf) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'UNREGISTERED', NOW()
                FROM trade_deal
                WHERE registered_date IS NULL
                  AND (cancel_yn IS NULL OR cancel_yn <> 'O')
                  AND DATE_ADD(deal_date, INTERVAL ? DAY) < ?
                """, snapshotDate, REGISTRATION_GRACE_DAYS, asOf);
    }

    private int insertDirect(LocalDate snapshotDate) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'DIRECT', NOW()
                FROM trade_deal WHERE deal_type = '직거래'
                """, snapshotDate);
    }
}
