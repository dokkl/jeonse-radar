package com.jeonseradar.ingest.repository;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// findKeysByStatus(IngestStatus)는 Spring Data가 메서드 이름을 파싱해 만드는 derived
// query이자, 닫힌 프로젝션(SuccessKey)의 getter를 엔티티 프로퍼티명과 매칭시켜야 하는
// 쿼리다. 이 결합은 컴파일 타임이 아니라 런타임에 풀린다 — IngestLog 필드명을 바꾸거나
// SuccessKey의 getter를 바꿔도 컴파일은 그대로 통과하고, 실행할 때만 깨지거나(매핑 실패)
// 조용히 엉뚱한 값을 돌려준다. 이 테스트가 지금까지 유일하게 실제 DB에 대고 이 메서드를
// 검증한다 — 기존 테스트는 전부 IngestLogRepository를 mock으로 대체해 왔다.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class IngestLogRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    IngestLogRepository repository;

    @Test
    void findKeysByStatus는_SUCCESS_행만_apiType_regionCode_dealYm으로_투영한다() {
        // 두 ApiType을 모두 SUCCESS로 섞어 심어, 프로젝션이 apiType까지 제대로 옮기는지
        // 확인한다. PENDING·FAILED — enum에 있는 SUCCESS 이외의 상태 전부 — 도 하나씩
        // 심어서, 조건절이 실제로는 걸러내지 않고 전체를 돌려주는 결함(derived query
        // 이름이 잘못 파싱된 경우 흔한 실패 형태)이라면 이 테스트가 잡아낸다.
        IngestLog rentSuccess = new IngestLog(ApiType.RENT, "11110", "202601");
        rentSuccess.record(IngestStatus.SUCCESS, 10, "00", 1);

        IngestLog tradeSuccess = new IngestLog(ApiType.TRADE, "11140", "202602");
        tradeSuccess.record(IngestStatus.SUCCESS, 5, "00", 1);

        IngestLog pending = new IngestLog(ApiType.RENT, "11170", "202603");
        // 생성자 기본값이 PENDING이므로 record()를 호출하지 않고 그대로 둔다.

        IngestLog failed = new IngestLog(ApiType.TRADE, "11200", "202604");
        failed.record(IngestStatus.FAILED, 0, "99", 3);

        repository.saveAll(List.of(rentSuccess, tradeSuccess, pending, failed));

        List<IngestLogRepository.SuccessKey> keys = repository.findKeysByStatus(IngestStatus.SUCCESS);

        assertThat(keys).hasSize(2);
        // row count만 보면 프로젝션이 세 getter 중 하나를 엉뚱한 컬럼에 매핑해도
        // (예: getDealYm()이 regionCode를 돌려줘도) 통과해 버린다. 세 getter를 각각
        // 명시적으로 읽어, SUCCESS로 심은 두 행의 값과 정확히 일치하는지 확인한다.
        assertThat(keys)
                .extracting(IngestLogRepository.SuccessKey::getApiType,
                        IngestLogRepository.SuccessKey::getRegionCode,
                        IngestLogRepository.SuccessKey::getDealYm)
                .containsExactlyInAnyOrder(
                        tuple(ApiType.RENT, "11110", "202601"),
                        tuple(ApiType.TRADE, "11140", "202602"));
    }
}
