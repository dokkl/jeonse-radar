package com.jeonseradar.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WebExceptionHandler}이 실제 HTTP 응답에 무엇을 싣는지 고정한다.
 *
 * DB나 전체 스프링 컨텍스트가 필요 없다 — 컨트롤러 하나와 예외 처리기만 있으면
 * MockMvc standalone 설정으로 충분하다. 네트워크나 Testcontainers를 타지 않는다.
 */
class WebExceptionHandlerTest {

    @Controller
    static class ThrowingController {
        @GetMapping("/throws-unavailable")
        void throwUnavailable() {
            throw new SnapshotUnavailableException();
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new WebExceptionHandler())
            .setSingleView((model, request, response) -> { })
            .build();

    @Test
    void 스냅샷이_없으면_503과_Retry_After_헤더를_준다() throws Exception {
        mockMvc.perform(get("/throws-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"));
    }
}
