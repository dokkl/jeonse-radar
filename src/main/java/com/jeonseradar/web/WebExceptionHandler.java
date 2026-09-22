package com.jeonseradar.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class WebExceptionHandler {

    /**
     * 503 + Retry-After. 200에 빈 페이지를 주면 검색엔진이 그 빈 페이지를 색인한다.
     * 503은 "일시적이니 나중에 다시 오라"는 정확한 신호다.
     */
    @ExceptionHandler(SnapshotUnavailableException.class)
    public ModelAndView unavailable() {
        ModelAndView mv = new ModelAndView("error/unavailable");
        mv.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        mv.addObject("retryAfter", 3600);
        return mv;
    }
}
