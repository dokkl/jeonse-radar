package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.entity.TradeDeal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * deal_hash UNIQUE + ON DUPLICATE KEY UPDATE로 멱등 저장한다.
 *
 * 갱신 대상은 가변 필드(등기일자, 해제여부, 해제일)뿐이다.
 * 나머지는 deal_hash를 구성하는 불변 필드이므로 값이 같음이 보장된다 (스펙 §1.3).
 */
@Repository
public class DealUpsertDao {

    private static final String TRADE_SQL = """
            INSERT INTO trade_deal
              (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun, apt_name,
               area, area_bucket, floor, deal_date, deal_amount, build_year,
               deal_type, agent_region, registered_date, cancel_yn, cancel_date,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              registered_date = VALUES(registered_date),
              cancel_yn       = VALUES(cancel_yn),
              cancel_date     = VALUES(cancel_date),
              complex_id      = VALUES(complex_id),
              updated_at      = NOW()
            """;

    private static final String RENT_SQL = """
            INSERT INTO rent_deal
              (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun, apt_name,
               area, area_bucket, floor, deal_date, deposit, monthly_rent, build_year,
               contract_type, contract_term_raw, term_start_ym, term_end_ym,
               term_estimated, renewal_right_used, prev_deposit, prev_monthly_rent,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              complex_id = VALUES(complex_id),
              updated_at = NOW()
            """;

    private final JdbcTemplate jdbcTemplate;

    public DealUpsertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int upsertTrades(List<TradeDeal> deals) {
        if (deals.isEmpty()) {
            return 0;
        }
        int affected = jdbcTemplate.batchUpdate(TRADE_SQL, deals, deals.size(),
                (PreparedStatement ps, TradeDeal d) -> {
                    ps.setString(1, d.getDealHash());
                    setLongOrNull(ps, 2, d.getComplexId());
                    ps.setString(3, d.getAptSeq());
                    ps.setString(4, d.getRegionCode());
                    ps.setString(5, d.getLegalDong());
                    ps.setString(6, d.getJibun());
                    ps.setString(7, d.getAptName());
                    ps.setBigDecimal(8, d.getArea());
                    ps.setInt(9, d.getAreaBucket());
                    ps.setInt(10, d.getFloor());
                    ps.setDate(11, Date.valueOf(d.getDealDate()));
                    ps.setLong(12, d.getDealAmount());
                    setIntOrNull(ps, 13, d.getBuildYear());
                    ps.setString(14, d.getDealType());
                    ps.setString(15, d.getAgentRegion());
                    setDateOrNull(ps, 16, d.getRegisteredDate());
                    ps.setString(17, d.getCancelYn());
                    setDateOrNull(ps, 18, d.getCancelDate());
                })[0].length;
        return affected;
    }

    @Transactional
    public int upsertRents(List<RentDeal> deals) {
        if (deals.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate(RENT_SQL, deals, deals.size(),
                (PreparedStatement ps, RentDeal d) -> {
                    ps.setString(1, d.getDealHash());
                    setLongOrNull(ps, 2, d.getComplexId());
                    ps.setString(3, d.getAptSeq());
                    ps.setString(4, d.getRegionCode());
                    ps.setString(5, d.getLegalDong());
                    ps.setString(6, d.getJibun());
                    ps.setString(7, d.getAptName());
                    ps.setBigDecimal(8, d.getArea());
                    ps.setInt(9, d.getAreaBucket());
                    ps.setInt(10, d.getFloor());
                    ps.setDate(11, Date.valueOf(d.getDealDate()));
                    ps.setLong(12, d.getDeposit());
                    ps.setLong(13, d.getMonthlyRent());
                    setIntOrNull(ps, 14, d.getBuildYear());
                    ps.setString(15, d.getContractType());
                    ps.setString(16, d.getContractTermRaw());
                    ps.setString(17, d.getTermStartYm());
                    ps.setString(18, d.getTermEndYm());
                    ps.setBoolean(19, Boolean.TRUE.equals(d.getTermEstimated()));
                    ps.setBoolean(20, Boolean.TRUE.equals(d.getRenewalRightUsed()));
                    setLongOrNull(ps, 21, d.getPrevDeposit());
                    setLongOrNull(ps, 22, d.getPrevMonthlyRent());
                });
        return deals.size();
    }

    private void setLongOrNull(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private void setIntOrNull(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setDateOrNull(PreparedStatement ps, int index, java.time.LocalDate value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }
}
