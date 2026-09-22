package com.jeonseradar.web.complex;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레저 P3-3: 정규 주소가 아닌 요청에 대한 301이 {@code Location} 헤더 없이 나가는 결함의
 * 회귀 테스트.
 *
 * <p>{@link ComplexPageControllerTest}의 MockMvc 기반 리다이렉트 테스트는 이 버그를 잡지
 * 못한다 — {@code MockHttpServletResponse}는 Location 값을 순수 자바 문자열로만 들고
 * 있어서 Tomcat의 헤더 인코더(ISO-8859-1)를 절대 거치지 않기 때문이다. 그래서 이 클래스는
 * 임베디드 Tomcat을 실제로 띄우는 {@code RANDOM_PORT}와 {@code TestRestTemplate}으로,
 * 서버가 실제로 내보내는 바이트를 검사한다. 보일러플레이트는 {@code ComplexPageControllerTest}
 * (Task 4)를 그대로 따른다.
 *
 * <p>네트워크를 타는 테스트를 만들지 않는다 — localhost의 RANDOM_PORT 서버와 Testcontainers
 * DB이므로 외부 네트워크 호출이 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ComplexPageRedirectEncodingTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    // 이 클래스패스에는 httpclient5가 없어서(web/thymeleaf/data-jpa/validation/mysql/
    // starter-test/testcontainers뿐) TestRestTemplate은 HttpURLConnection 기반
    // SimpleClientHttpRequestFactory로 폴백한다. 그런데 그 기본 prepareConnection()은
    // GET에 한해 connection.setInstanceFollowRedirects(true)를 스스로 건다 — 즉
    // 기본값으로는 리다이렉트를 "따라간다". 301 자체를 검사해야 하는 이 테스트에서는
    // 그게 정확히 우리가 원치 않는 동작이므로, super 호출 뒤에 다시 false로 되돌리는
    // 팩토리를 직접 붙여 명시적으로 끈다 — 새 의존성(httpclient5)을 더해 기본값을
    // 바꾸는 대신, 메서드 하나를 오버라이드해서 의도를 호출부에 남긴다.
    private final TestRestTemplate restTemplate = new TestRestTemplate(new RestTemplateBuilder()
            .requestFactory(() -> new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                        throws IOException {
                    super.prepareConnection(connection, httpMethod);
                    connection.setInstanceFollowRedirects(false);
                }
            }));

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 0, 0);
        snapshots.save(s);

        // 정규 slug 자체에도 한글이 들어가는 단지 — path()가 "/단지/..." 접두사만으로도
        // 이미 인코딩 대상이라는 점과, slug 안의 한글도 함께 인코딩된다는 점을 같이 검증한다.
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (1, '11710-1', '11710', '잠실동', '40', '헬리오시티', '헬리오시티-11710-1', 2018)
                """);
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, 1, 84, 950000000, 30, 'L1', 'HIGH')
                """, SNAPSHOT);
    }

    @Test
    void 실서버는_정규_주소가_아니면_퍼센트_인코딩된_Location으로_301한다() {
        String requestUrl = "http://localhost:" + port + "/단지/1-옛날slug";

        ResponseEntity<String> response = restTemplate.getForEntity(requestUrl, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);

        URI location = response.getHeaders().getLocation();
        assertThat(location).as("Location 헤더가 존재해야 한다 (버그 상태에서는 Tomcat이 통째로 제거한다)")
                .isNotNull();
        assertThat(location.toString()).isNotBlank();

        String canonicalPath = "/단지/1-헬리오시티-11710-1";
        String expectedEncodedPath = UriUtils.encodePath(canonicalPath, StandardCharsets.UTF_8);

        assertThat(location.getRawPath()).isEqualTo(expectedEncodedPath);
        assertThat(UriUtils.decode(location.getRawPath(), StandardCharsets.UTF_8))
                .isEqualTo(canonicalPath);
    }
}
