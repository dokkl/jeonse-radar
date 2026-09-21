package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.TradeDeal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 이 테스트 클래스의 모든 @Test는 같은 Spring 컨텍스트/컨테이너, 즉 같은 trade_deal 테이블을
// 공유한다. 각 테스트가 repository.count()에 대해 절대값(1건, 2건)을 단정하므로, 이전 테스트가
// 남긴 행이 그대로 남아 있으면 누적되어 실패한다(예: 5개 테스트가 쓰는 7개 해시가 전부 쌓여
// count()가 7이 되는 식). @Transactional을 클래스에 붙이면 Spring 테스트 트랜잭션 매니저가
// 각 테스트 메서드 종료 후 자동 롤백해 테스트 간 격리를 보장한다 — 단정문 자체는 그대로 둔다.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class DealUpsertDaoTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    DealUpsertDao dao;

    @Autowired
    TradeDealRepository repository;

    /** 테스트 픽스처. TradeDeal.of 는 Task 4에서 이미 만들어져 있다. */
    private TradeDeal sample(String hash, String cancelYn, LocalDate registeredDate) {
        return TradeDeal.of(
                hash, 1L, "11110-2446", "11110", "평동", "233", "경희궁자이(3단지)",
                new java.math.BigDecimal("59.85"), 60, 2,
                LocalDate.of(2026, 3, 7), 2_300_000_000L, 2017,
                "중개거래", "서울 종로구", registeredDate, cancelYn, null);
    }

    @Test
    void 같은_거래를_두번_수집해도_행이_늘지_않는다() {
        dao.upsertTrades(List.of(sample("hash-a", "N", null)));
        dao.upsertTrades(List.of(sample("hash-a", "N", null)));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 해제여부가_바뀌면_기존_행이_갱신된다() {
        dao.upsertTrades(List.of(sample("hash-b", "N", null)));
        dao.upsertTrades(List.of(sample("hash-b", "O", null)));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByDealHash("hash-b").orElseThrow().getCancelYn())
                .isEqualTo("O");
    }

    @Test
    void 등기일자가_나중에_채워지면_갱신된다() {
        dao.upsertTrades(List.of(sample("hash-c", "N", null)));
        dao.upsertTrades(List.of(sample("hash-c", "N", LocalDate.of(2026, 5, 20))));

        assertThat(repository.findByDealHash("hash-c").orElseThrow().getRegisteredDate())
                .isEqualTo(LocalDate.of(2026, 5, 20));
    }

    @Test
    void 서로_다른_해시는_별개_행이다() {
        dao.upsertTrades(List.of(sample("hash-d", "N", null), sample("hash-e", "N", null)));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void 배치_중간에_중복이_있어도_전체가_저장된다() {
        dao.upsertTrades(List.of(
                sample("hash-f", "N", null),
                sample("hash-f", "N", null),
                sample("hash-g", "N", null)));

        assertThat(repository.count()).isEqualTo(2);
    }
}
