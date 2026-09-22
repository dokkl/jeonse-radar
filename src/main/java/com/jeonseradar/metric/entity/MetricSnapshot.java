package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 집계 스냅샷의 메타 정보.
 *
 * 집계 테이블에 snapshot_date만 두면 "이 스냅샷이 다 만들어진 것인지" 알 수 없어,
 * 빌드 도중의 반쪽짜리 결과가 화면에 샐 수 있다 (스펙 §1.7).
 * 조회 계층은 반드시 이 테이블에서 COMPLETE인 최신 snapshot_date를 먼저 찾는다.
 */
@Entity
@Table(name = "metric_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_metric_snapshot_date",
                columnNames = "snapshot_date"))
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private SnapshotStatus status;

    private Integer rentStatRows;
    private Integer maturityRows;
    private Integer dealFlagRows;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(length = 500)
    private String failureReason;

    protected MetricSnapshot() {
    }

    public static MetricSnapshot start(LocalDate snapshotDate) {
        MetricSnapshot s = new MetricSnapshot();
        s.snapshotDate = snapshotDate;
        s.status = SnapshotStatus.BUILDING;
        s.startedAt = LocalDateTime.now();
        return s;
    }

    public void complete(int rentStatRows, int maturityRows, int dealFlagRows) {
        this.status = SnapshotStatus.COMPLETE;
        this.rentStatRows = rentStatRows;
        this.maturityRows = maturityRows;
        this.dealFlagRows = dealFlagRows;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = SnapshotStatus.FAILED;
        this.failureReason = reason == null ? null
                : reason.substring(0, Math.min(reason.length(), 500));
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public SnapshotStatus getStatus() { return status; }
    public Integer getRentStatRows() { return rentStatRows; }
    public Integer getMaturityRows() { return maturityRows; }
    public Integer getDealFlagRows() { return dealFlagRows; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getFailureReason() { return failureReason; }
}
