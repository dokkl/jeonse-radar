package com.jeonseradar.web;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 공개 페이지가 읽어야 할 스냅샷 날짜를 해석한다. **모든 조회는 이 관문을 통과한다.**
 *
 * 2단계는 "COMPLETE인 스냅샷만 읽힌다"를 metric_snapshot.status로 보장했다. 그러나 그
 * 보장은 읽는 쪽이 status를 실제로 확인해야 완성된다. 조회 서비스마다 각자
 * findLatestComplete()를 부르게 두면 언젠가 한 곳이 빠지고, 그 한 곳이 반쪽 스냅샷을
 * 노출한다. 관문을 하나로 둔다.
 *
 * 캐시하지 않는다. 야간 배치가 새 스냅샷을 올리면 다음 요청부터 바로 반영돼야 하고,
 * 조회 한 번은 인덱스를 탄 단일 행 읽기라 캐시할 만큼 비싸지 않다.
 */
@Service
public class PublishedSnapshot {

    private final MetricSnapshotRepository snapshots;

    public PublishedSnapshot(MetricSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** @throws SnapshotUnavailableException 완료된 스냅샷이 하나도 없을 때 */
    public LocalDate date() {
        return snapshots.findLatestComplete()
                .map(MetricSnapshot::getSnapshotDate)
                .orElseThrow(SnapshotUnavailableException::new);
    }
}
