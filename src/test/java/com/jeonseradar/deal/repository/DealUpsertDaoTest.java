package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.entity.TradeDeal;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 이 테스트 클래스의 모든 @Test는 같은 Spring 컨텍스트/컨테이너, 즉 같은 trade_deal 테이블을
// 공유한다. 각 테스트가 repository.count()에 대해 절대값(1건, 2건)을 단정하므로, 이전 테스트가
// 남긴 행이 그대로 남아 있으면 누적되어 실패한다(예: 5개 테스트가 쓰는 7개 해시가 전부 쌓여
// count()가 7이 되는 식). @Transactional을 클래스에 붙이면 Spring 테스트 트랜잭션 매니저가
// 각 테스트 메서드 종료 후 자동 롤백해 테스트 간 격리를 보장한다 — 단정문 자체는 그대로 둔다.
@SpringBootTest
@Transactional
class DealUpsertDaoTest extends IntegrationTestSupport {

    @Autowired
    DealUpsertDao dao;

    @Autowired
    TradeDealRepository repository;

    @Autowired
    RentDealRepository rentRepository;

    /** 테스트 픽스처. TradeDeal.of 는 Task 4에서 이미 만들어져 있다. */
    private TradeDeal sample(String hash, String cancelYn, LocalDate registeredDate) {
        return TradeDeal.of(
                hash, 1L, "11110-2446", "11110", "평동", "233", "경희궁자이(3단지)",
                new java.math.BigDecimal("59.85"), 60, 2,
                LocalDate.of(2026, 3, 7), 2_300_000_000L, 2017,
                "중개거래", "서울 종로구", registeredDate, cancelYn, null);
    }

    /** 테스트 픽스처. RentDeal.of 는 Task 4에서 이미 만들어져 있다. */
    private RentDeal sampleRent(String hash) {
        return RentDeal.of(
                hash, 1L, "11110-2446", "11110", "평동", "233", "경희궁자이(3단지)",
                new java.math.BigDecimal("59.85"), 60, 2,
                LocalDate.of(2026, 3, 7), 500_000_000L, 0L, 2017,
                "전세", "24개월", "202603", "202803", false, false, null, null);
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

    // rent_deal에는 매매의 등기일자·해제여부처럼 나중에 값이 바뀌는 가변 필드가 없다
    // (DealUpsertDao Step 6 주석 참고 — ON DUPLICATE KEY UPDATE 절이 complex_id,
    // updated_at만 갱신하는 이유). 그래서 "필드가 바뀌면 기존 행이 갱신된다"에 대응하는
    // rent 케이스는 존재하지 않는다 — 억지로 만들지 않고, 멱등성(중복 삽입 방지)만 검증한다.

    @Test
    void 같은_임대차를_두번_수집해도_행이_늘지_않는다() {
        dao.upsertRents(List.of(sampleRent("rent-hash-a")));
        dao.upsertRents(List.of(sampleRent("rent-hash-a")));

        assertThat(rentRepository.count()).isEqualTo(1);
    }

    @Test
    void 임대차는_서로_다른_해시면_별개_행이다() {
        dao.upsertRents(List.of(sampleRent("rent-hash-b"), sampleRent("rent-hash-c")));

        assertThat(rentRepository.count()).isEqualTo(2);
    }

    @Test
    void 임대차_배치_중간에_중복이_있어도_전체가_저장된다() {
        dao.upsertRents(List.of(
                sampleRent("rent-hash-d"),
                sampleRent("rent-hash-d"),
                sampleRent("rent-hash-e")));

        assertThat(rentRepository.count()).isEqualTo(2);
    }

    @Test
    void 커밋된_트랜잭션을_넘나들며_재수집해도_행이_늘지_않는다() {
        // 클래스 레벨 @Transactional은 각 테스트 메서드를 하나의 트랜잭션으로 감싸고 끝에서
        // 롤백한다 — 이는 "같은 트랜잭션 내에서의 멱등성"만 증명한다. 그러나 실제 운영에서는
        // 1일차 수집이 커밋되고, 3일차 재수집은 완전히 새로운(별도로 커밋되는) 트랜잭션에서
        // 일어난다. TestTransaction으로 실제 커밋 경계를 만들어 그 시나리오를 재현한다.
        dao.upsertTrades(List.of(sample("hash-commit", "N", null)));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        dao.upsertTrades(List.of(sample("hash-commit", "N", null)));

        assertThat(repository.count()).isEqualTo(1);

        // 위에서 커밋한 행은 클래스 레벨 @Transactional의 자동 롤백으로 지워지지 않는다.
        // 다음 테스트로의 누수(이전에 겪었던 count 누적 문제)를 막기 위해 이 테스트가
        // 커밋한 행은 이 테스트가 직접 지우고, 그 삭제 역시 커밋해 실제로 반영시킨다.
        repository.findByDealHash("hash-commit").ifPresent(repository::delete);
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }
}
