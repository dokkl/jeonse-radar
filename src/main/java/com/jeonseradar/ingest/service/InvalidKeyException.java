package com.jeonseradar.ingest.service;

/** 서비스키 오류. 재시도해도 무의미하므로 즉시 중단한다 (스펙 §1.6). */
public class InvalidKeyException extends RuntimeException {
    public InvalidKeyException(String message) {
        super(message);
    }
}
