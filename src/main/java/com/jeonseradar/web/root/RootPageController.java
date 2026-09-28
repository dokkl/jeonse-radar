package com.jeonseradar.web.root;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootPageController {

    private final RootPageQuery query;
    private final String baseUrl;

    public RootPageController(RootPageQuery query,
                              @Value("${jeonse.base-url:https://example.invalid}") String baseUrl) {
        this.query = query;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("page", query.load());
        model.addAttribute("robots", null);
        model.addAttribute("baseUrl", baseUrl);
        return "root";
    }
}
