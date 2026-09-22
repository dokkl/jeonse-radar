package com.jeonseradar.metric.entity;

public enum SnapshotStatus {

    /** 빌드 진행 중. 조회 대상이 아니다. */
    BUILDING,

    /** 빌드 완료. 조회는 이 상태의 최신 스냅샷만 본다. */
    COMPLETE,

    /** 빌드 실패. 직전 COMPLETE 스냅샷이 계속 서비스된다. */
    FAILED
}
