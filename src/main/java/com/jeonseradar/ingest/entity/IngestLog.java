package com.jeonseradar.ingest.entity;

import com.jeonseradar.ingest.client.ApiType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ingest_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ingest_target",
                columnNames = {"apiType", "regionCode", "dealYm"}))
public class IngestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private ApiType apiType;

    @Column(length = 5, nullable = false)
    private String regionCode;

    @Column(length = 6, nullable = false)
    private String dealYm;

    @Enumerated(EnumType.STRING)
    @Column(length = 8, nullable = false)
    private IngestStatus status;

    private Integer rowCount;

    @Column(length = 8)
    private String resultCode;

    private Integer attempts;

    private LocalDateTime lastAttemptAt;

    protected IngestLog() {
    }

    public IngestLog(ApiType apiType, String regionCode, String dealYm) {
        this.apiType = apiType;
        this.regionCode = regionCode;
        this.dealYm = dealYm;
        this.status = IngestStatus.PENDING;
        this.attempts = 0;
        this.rowCount = 0;
    }

    public void record(IngestStatus status, int rowCount, String resultCode, int attempts) {
        this.status = status;
        this.rowCount = rowCount;
        this.resultCode = resultCode;
        this.attempts = attempts;
        this.lastAttemptAt = LocalDateTime.now();
    }

    public IngestStatus getStatus() {
        return status;
    }

    public Integer getRowCount() {
        return rowCount;
    }
}
