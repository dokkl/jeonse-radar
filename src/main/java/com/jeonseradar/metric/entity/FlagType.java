package com.jeonseradar.metric.entity;

/**
 * 매매 신고가 검증 플래그 (스펙 §3.7).
 *
 * 사실만 나타내며 판단을 담지 않는다. "자전거래 의심" 같은 단정은 만들지 않는다.
 */
public enum FlagType {

    /** 신고 후 해제된 거래 */
    CANCELED,

    /** 계약 후 60일이 지났는데 등기가 확인되지 않은 거래 */
    UNREGISTERED,

    /** 중개 없이 이루어진 직거래 */
    DIRECT
}
