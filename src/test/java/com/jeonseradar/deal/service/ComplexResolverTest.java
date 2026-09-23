package com.jeonseradar.deal.service;

import com.jeonseradar.deal.repository.ComplexRepository;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ComplexResolverTest extends IntegrationTestSupport {

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
    void 표시명은_다른_이름으로_다시_조회해도_처음_이름을_유지한다() {
        // 최빈값 수렴 로직을 두지 않은 설계 판단(브리프 "왜 지번이 아니라 aptSeq인가")을
        // 직접 검증한다. 위 표시명은_처음_본_이름을_유지한다()는 두 번 다 같은 이름을 넘기므로
        // resolve()가 displayName을 최신 이름으로 덮어쓰도록 바뀌어도 여전히 통과한다.
        // 이름이 실제로 "바뀌는" 경우를 넣어야 그 회귀를 잡을 수 있다.
        Long id = resolver.resolve("11110", "11110-92", "행촌동", "12", "첫번째이름", 2000);
        resolver.resolve("11110", "11110-92", "행촌동", "12", "두번째이름", 2000);

        assertThat(repository.findById(id).orElseThrow().getDisplayName())
                .isEqualTo("첫번째이름");
    }

    @Test
    void slug는_단지마다_고유하다() {
        Long a = resolver.resolve("11110", "11110-500", "청운동", "200", "자이", 2015);
        Long b = resolver.resolve("11110", "11110-501", "청운동", "201", "자이", 2015);

        String slugA = repository.findById(a).orElseThrow().getSlug();
        String slugB = repository.findById(b).orElseThrow().getSlug();

        assertThat(slugA).isNotEqualTo(slugB);
    }

    @Test
    void slug가_150자_제한으로_잘려_충돌하면_숫자_접미사로_구분된다() {
        // SlugGenerator.generate()는 "이름-aptSeq"를 150자로 자른다. 이름이
        // Complex.displayName 컬럼 한도(128자)에 가까울 만큼 길면 aptSeq 뒷부분이
        // 잘려나가고, 서로 다른 aptSeq라도 잘린 slug가 같아질 수 있다 — 이 테스트는
        // uniqueSlug()의 숫자 접미사 재시도 루프를 실제로 통과시킨다.
        String 컬럼_한도까지_채운_이름 = "가".repeat(128);
        String aptSeq_공통_접두부 = "1".repeat(25);

        Long a = resolver.resolve("11110", aptSeq_공통_접두부 + "0001", "행촌동", "1",
                컬럼_한도까지_채운_이름, 2000);
        Long b = resolver.resolve("11110", aptSeq_공통_접두부 + "0002", "행촌동", "2",
                컬럼_한도까지_채운_이름, 2000);

        String slugA = repository.findById(a).orElseThrow().getSlug();
        String slugB = repository.findById(b).orElseThrow().getSlug();

        assertThat(slugA).isNotNull();
        assertThat(slugB).isNotNull();
        assertThat(slugA).isNotEqualTo(slugB);
        assertThat(slugB).endsWith("-2");
    }
}
