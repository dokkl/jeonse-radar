package com.jeonseradar.web.region;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Controller
public class RegionPageController {

    private final RegionPageQuery query;
    private final SigunguCatalog catalog;

    public RegionPageController(RegionPageQuery query, SigunguCatalog catalog) {
        this.query = query;
        this.catalog = catalog;
    }

    @GetMapping("/역전세/{sido}")
    public String sidoIndex(@PathVariable String sido, Model model) {
        List<String> codes = query.sigunguCodesOf(sido);
        if (codes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 세종특별자치시처럼 시군구가 없는 시도는 인덱스 자리가 곧 지역 페이지다 (원장 R-3).
        if (codes.size() == 1 && !RegionName.of(codes.get(0), catalog.nameOf(codes.get(0))).hasSigungu()) {
            return renderRegion(codes.get(0), model);
        }

        List<RegionName> names = codes.stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .toList();
        model.addAttribute("sido", sido);
        model.addAttribute("regions", names);
        model.addAttribute("robots", null);
        return "sido";
    }

    @GetMapping("/역전세/{sido}/{sigungu}")
    public String region(@PathVariable String sido, @PathVariable String sigungu, Model model) {
        String code = codeOf(sido, sigungu)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return renderRegion(code, model);
    }

    private String renderRegion(String code, Model model) {
        RegionPage page = query.find(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("page", page);
        model.addAttribute("robots", page.indexable() ? null : "noindex, follow");
        return "region";
    }

    /**
     * URL에는 이름이, 조회에는 코드가 필요하다. 카탈로그 269행을 순회해 되돌린다.
     * 요청당 한 번이고 전부 메모리에 있으므로 인덱스를 미리 만들지 않는다.
     */
    private Optional<String> codeOf(String sido, String sigunguSegment) {
        return catalog.all().stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .filter(n -> n.sido().equals(sido) && sigunguSegment.equals(n.sigunguSegment()))
                .map(RegionName::code)
                .findFirst();
    }
}
