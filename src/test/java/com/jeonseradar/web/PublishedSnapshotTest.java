package com.jeonseradar.web;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(SpringExtension.class)
class PublishedSnapshotTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired PublishedSnapshot published;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        snapshots.deleteAll();
    }

    @Test
    void 완료된_스냅샷이_없으면_예외를_던진다() {
        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 완료된_스냅샷이_있으면_그_날짜를_준다() {
        MetricSnapshot s = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        s.complete(10, 20, 30);
        snapshots.save(s);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void 빌드_중인_스냅샷은_발행으로_치지_않는다() {
        snapshots.save(MetricSnapshot.start(LocalDate.of(2026, 9, 22)));

        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 실패한_스냅샷은_발행으로_치지_않는다() {
        MetricSnapshot s = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        s.fail("집계 실패");
        snapshots.save(s);

        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 완료본이_여러_날이면_가장_최근을_준다() {
        MetricSnapshot older = MetricSnapshot.start(LocalDate.of(2026, 9, 20));
        older.complete(1, 1, 1);
        snapshots.save(older);

        MetricSnapshot newer = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        newer.complete(1, 1, 1);
        snapshots.save(newer);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void 더_최근_날짜가_실패해도_직전_완료본이_계속_서빙된다() {
        MetricSnapshot good = MetricSnapshot.start(LocalDate.of(2026, 9, 21));
        good.complete(1, 1, 1);
        snapshots.save(good);

        MetricSnapshot bad = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        bad.fail("집계 실패");
        snapshots.save(bad);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 21));
    }
}
