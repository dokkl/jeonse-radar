package com.jeonseradar.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;

/**
 * DB가 필요한 통합 테스트의 공통 토대.
 *
 * <p>예전에는 17개 클래스가 저마다 {@code @Testcontainers} + {@code @Container}로 자기
 * MySQL을 띄웠다. JUnit이 클래스마다 컨테이너를 start/stop 하므로 컨테이너 기동 + 헬스체크 +
 * 스프링 컨텍스트 + Hibernate DDL을 17번 반복했고, 클래스마다 datasource URL이 달라져
 * 컨텍스트 캐시 키까지 전부 달라졌다(컨텍스트도 17개). 테스트 본문 자체는 10초 남짓인데
 * 벽시계는 11분이 넘었다.
 *
 * <p>그래서 컨테이너를 {@code static}으로 한 번만 띄우고(테스트컨테이너 싱글턴 패턴)
 * 절대 stop 하지 않는다 — 수명을 클래스에 묶는 것이 바로 {@code @Testcontainers}/
 * {@code @Container}이므로 둘 다 쓰지 않는다. JVM이 끝나면 Ryuk이 회수한다. 세 프로퍼티를
 * 여기 한 곳에서 등록하므로 모든 하위 클래스의 컨텍스트 캐시 키가 같아지고, 실제로 만들어지는
 * 컨텍스트는 {@code @SpringBootTest} 설정이 다른 세 종류(기본 / MockMvc / RANDOM_PORT)뿐이다.
 *
 * <p>대신 "클래스마다 새 DB"라는 격리가 사라진다. 그 격리는 이제 아래 {@link #공유_데이터베이스를_비운다()}가
 * 중앙에서 책임진다 — 클래스가 잊어버릴 수도, 각자 다른 목록을 들고 있을 수도 없다.
 *
 * <p>네트워크를 타는 테스트를 만들지 않는다 — 로컬 도커 컨테이너이며 외부 호출이 아니다.
 */
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    /**
     * 컨테이너 하나를 JVM 전체가 공유한다. {@code @Container}를 붙이지 않는 것이 핵심이다 —
     * 붙이는 순간 JUnit이 클래스마다 start/stop 한다.
     */
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * 자식 → 부모 순서. 지금 엔티티에는 JPA 연관관계가 없어 Hibernate가 FK를 만들지 않지만,
     * 나중에 FK가 생겨도 이 순서 그대로 안전하도록 논리적 참조 방향을 따라 지운다
     * (deal_flag → trade_deal, maturity_forecast·complex_rent_stat → complex).
     *
     * <p>목록은 기존 12개 클래스가 각자 들고 있던 DELETE의 합집합이다
     * (maturity_forecast, complex_rent_stat, complex, metric_snapshot, rent_deal,
     * trade_deal, deal_flag) + 이제 DB를 공유하게 된 IngestLogRepositoryTest를 위한
     * ingest_log. 즉 중앙 정리가 기존 정리보다 좁아지지 않는다.
     */
    private static final List<String> 삭제_순서 = List.of(
            "deal_flag",
            "maturity_forecast",
            "complex_rent_stat",
            "metric_snapshot",
            "rent_deal",
            "trade_deal",
            "complex",
            "ingest_log");

    @Autowired
    private JdbcTemplate 정리용_jdbc;

    /**
     * 모든 테스트 메서드 앞에서 공유 DB를 비운다. JUnit은 상위 클래스의 {@code @BeforeEach}를
     * 하위 클래스의 것보다 먼저 실행하므로, 데이터를 심는 하위 클래스의 {@code @BeforeEach}는
     * 그대로 두면 된다.
     *
     * <p>주입받은 {@code JdbcTemplate}을 쓰므로 클래스 레벨 {@code @Transactional}이 걸린
     * 테스트에서는 이 DELETE도 테스트 트랜잭션 안에서 실행된다 — 그래야 삭제 결과가 그 테스트에
     * 확실히 보인다. 트랜잭션이 없는 클래스에서는 그대로 커밋된다.
     */
    @BeforeEach
    protected void 공유_데이터베이스를_비운다() {
        삭제_순서.forEach(table -> 정리용_jdbc.update("DELETE FROM " + table));
    }
}
