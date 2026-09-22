package com.jeonseradar.web;

/** 발행 가능한(COMPLETE) 스냅샷이 아직 없다. 일시적 상태이므로 503으로 응답한다. */
public class SnapshotUnavailableException extends RuntimeException {

    public SnapshotUnavailableException() {
        super("발행된 스냅샷이 없다. 아직 집계가 한 번도 완료되지 않았다.");
    }
}
