package com.jeonseradar.deal.service;

import com.jeonseradar.deal.entity.Complex;
import com.jeonseradar.deal.repository.ComplexRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단지 식별은 아파트명도 지번도 아닌 aptSeq 기준이다.
 *
 * 아파트명은 두 API 간 표기가 흔들리고, 지번은 서로 다른 단지 여럿을 한 값으로 합쳐버린다
 * (실측 43건). aptSeq는 채움률 100%이고 한 값이 여러 지번에 걸치거나 이름이 흔들리는
 * 사례가 실측 0건이므로, 최빈값 수렴 같은 보정 로직 없이 그대로 키로 쓸 수 있다 (스펙 §1.4).
 */
@Service
public class ComplexResolver {

    private final ComplexRepository repository;

    public ComplexResolver(ComplexRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Long resolve(String regionCode, String aptSeq, String legalDong, String jibun,
                        String aptName, Integer buildYear) {
        return repository.findByRegionCodeAndAptSeq(regionCode, aptSeq)
                .orElseGet(() -> repository.save(new Complex(
                        regionCode, aptSeq, legalDong, jibun,
                        aptName, buildYear,
                        uniqueSlug(aptName, aptSeq))))
                .getId();
    }

    private String uniqueSlug(String aptName, String aptSeq) {
        String slug = SlugGenerator.generate(aptName, aptSeq);
        if (!repository.existsBySlug(slug)) {
            return slug;
        }
        int suffix = 2;
        while (repository.existsBySlug(slug + "-" + suffix)) {
            suffix++;
        }
        return slug + "-" + suffix;
    }
}
