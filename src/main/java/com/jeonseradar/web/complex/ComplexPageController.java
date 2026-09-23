package com.jeonseradar.web.complex;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class ComplexPageController {

    private final ComplexPageQuery query;
    private final String baseUrl;

    public ComplexPageController(ComplexPageQuery query,
                                  @Value("${jeonse.base-url:https://example.invalid}") String baseUrl) {
        this.query = query;
        this.baseUrl = baseUrl;
    }

    /**
     * 반환형이 Object인 이유: 정규 주소가 아닐 때는 뷰 이름 대신 RedirectView를 돌려준다.
     * `return "redirect:..."`는 302를 내는데, 영구 이전이므로 301이어야 색인이 정규 주소로
     * 옮겨간다.
     */
    @GetMapping("/단지/{idAndSlug}")
    public Object page(@PathVariable String idAndSlug, Model model) {
        long id = parseId(idAndSlug);
        ComplexPage page = query.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // 같은 단지가 여러 주소로 색인되지 않도록 정규 주소 하나로 모은다.
        if (!idAndSlug.equals(id + "-" + page.slug())) {
            RedirectView redirect = new RedirectView(page.path());
            redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return redirect;
        }

        model.addAttribute("page", page);
        model.addAttribute("robots", page.indexable() ? null : "noindex, follow");
        model.addAttribute("baseUrl", baseUrl);
        return "complex";
    }

    /** `123-헬리오시티-11710-1` 에서 앞의 123만 뽑는다. 숫자가 아니면 404. */
    private long parseId(String idAndSlug) {
        int dash = idAndSlug.indexOf('-');
        String raw = dash < 0 ? idAndSlug : idAndSlug.substring(0, dash);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
