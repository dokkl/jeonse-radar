package com.jeonseradar.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class WebExceptionHandler {

    /** 발행 가능한 스냅샷이 없을 때 다시 시도하라고 알려줄 간격(초). */
    private static final long RETRY_AFTER_SECONDS = 3600;

    /**
     * 503 + Retry-After 헤더. 200에 빈 페이지를 주면 검색엔진이 그 빈 페이지를 색인한다.
     * 503은 "일시적이니 나중에 다시 오라"는 정확한 신호이고, Retry-After 헤더가 그
     * 신호를 기계가 읽을 수 있는 형태로 응답에 싣는다. 모델이 아니라 실제 HTTP
     * 헤더에 실어야 크롤러와 리버스 프록시가 읽는다.
     */
    @ExceptionHandler(SnapshotUnavailableException.class)
    public ModelAndView unavailable(HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        ModelAndView mv = new ModelAndView("error/unavailable");
        mv.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        return mv;
    }
}
