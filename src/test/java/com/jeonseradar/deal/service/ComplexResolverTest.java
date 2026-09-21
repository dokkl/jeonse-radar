package com.jeonseradar.deal.service;

import com.jeonseradar.deal.repository.ComplexRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ComplexResolverTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    ComplexResolver resolver;

    @Autowired
    ComplexRepository repository;

    @Test
    void 같은_aptSeq는_같은_단지다() {
        Long first = resolver.resolve("11110", "11110-2519", "무악동", "89", "경희궁롯데캐슬", 2019);
        Long second = resolver.resolve("11110", "11110-2519", "무악동", "89", "경희궁롯데캐슬", 2019);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 아파트명_표기가_달라도_aptSeq가_같으면_같은_단지다() {
        Long a = resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이(3단지)", 2017);
        Long b = resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이 3단지", 2017);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 같은_지번이라도_aptSeq가_다르면_다른_단지로_분리된다() {
        // 실측 43건의 사례. 지번 기준이었다면 두 단지가 하나로 합쳐졌을 것이다.
        // 예: 강남구 개포동 12 -> 11680-279, 11680-280
        Long a = resolver.resolve("11680", "11680-279", "개포동", "12", "개포주공1단지", 1982);
        Long b = resolver.resolve("11680", "11680-280", "개포동", "12", "개포주공2단지", 1982);

        assertThat(a).isNotEqualTo(b);
        assertThat(repository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 시군구가_다르면_다른_단지다() {
        Long a = resolver.resolve("11110", "11110-1", "중앙동", "1", "한화아파트", 2010);
        Long b = resolver.resolve("11170", "11170-1", "중앙동", "1", "한화아파트", 2010);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 표시명은_처음_본_이름을_유지한다() {
        Long id = resolver.resolve("11110", "11110-91", "창신동", "703", "창신쌍용2", 1993);
        resolver.resolve("11110", "11110-91", "창신동", "703", "창신쌍용2", 1993);

        assertThat(repository.findById(id).orElseThrow().getDisplayName())
                .isEqualTo("창신쌍용2");
    }

    @Test
    void slug는_단지마다_고유하다() {
        Long a = resolver.resolve("11110", "11110-500", "청운동", "200", "자이", 2015);
        Long b = resolver.resolve("11110", "11110-501", "청운동", "201", "자이", 2015);

        String slugA = repository.findById(a).orElseThrow().getSlug();
        String slugB = repository.findById(b).orElseThrow().getSlug();

        assertThat(slugA).isNotEqualTo(slugB);
    }
}
