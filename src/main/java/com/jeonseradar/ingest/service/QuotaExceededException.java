package com.jeonseradar.ingest.service;

/** 일일 트래픽 초과. 재시도하면 상황이 악화되므로 당일 배치 전체를 중단시킨다 (스펙 §1.6). */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
