package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record IngestResult(IngestStatus status, int rowCount, String resultCode) {
    }

    private final MolitApiClient client;
    private final TradeDealMapper tradeMapper;
    private final RentDealMapper rentMapper;
    private final DealUpsertDao upsertDao;
    private final IngestLogRepository logRepository;
    private final int maxRetries;

    public IngestService(MolitApiClient client,
                         TradeDealMapper tradeMapper,
                         RentDealMapper rentMapper,
                         DealUpsertDao upsertDao,
                         IngestLogRepository logRepository,
                         @Value("${molit.max-retries}") int maxRetries) {
        this.client = client;
        this.tradeMapper = tradeMapper;
        this.rentMapper = rentMapper;
        this.upsertDao = upsertDao;
        this.logRepository = logRepository;
        this.maxRetries = maxRetries;
    }

    public IngestResult ingestOne(ApiType apiType, String lawdCd, String dealYmd) {
        IngestLog logEntry = logRepository
                .findByApiTypeAndRegionCodeAndDealYm(apiType, lawdCd, dealYmd)
                .orElseGet(() -> new IngestLog(apiType, lawdCd, dealYmd));

        String lastCode = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ParsedResponse response = client.fetch(apiType, lawdCd, dealYmd);
                lastCode = response.rawCode();

                switch (response.code()) {
                    case OK -> {
                        int saved = save(apiType, response);
                        return finish(logEntry, IngestStatus.SUCCESS, saved, lastCode, attempt);
                    }
                    // 거래가 없는 지역은 0건이 정상이다. 실패로 남기면 재시도 큐가
                    // 영원히 비워지지 않는다 (스펙 §1.6).
                    case NODATA -> {
                        return finish(logEntry, IngestStatus.SUCCESS, 0, lastCode, attempt);
                    }
                    case QUOTA_EXCEEDED -> {
                        finish(logEntry, IngestStatus.PENDING, 0, lastCode, attempt);
                        throw new QuotaExceededException(
                                "일일 트래픽 초과: " + apiType + " " + lawdCd + " " + dealYmd);
                    }
                    case INVALID_KEY -> {
                        finish(logEntry, IngestStatus.FAILED, 0, lastCode, attempt);
                        throw new InvalidKeyException("서비스키 오류: " + lastCode);
                    }
                    case ERROR -> backoff(attempt);
                }
            } catch (QuotaExceededException | InvalidKeyException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("수집 실패 ({}회차) {} {} {}: {}",
                        attempt, apiType, lawdCd, dealYmd, e.toString());
                backoff(attempt);
            }
        }

        return finish(logEntry, IngestStatus.FAILED, 0, lastCode, maxRetries);
    }

    private int save(ApiType apiType, ParsedResponse response) {
        if (apiType == ApiType.TRADE) {
            var deals = response.items().stream().map(tradeMapper::map).toList();
            upsertDao.upsertTrades(deals);
            return deals.size();
        }
        var deals = response.items().stream().map(rentMapper::map).toList();
        upsertDao.upsertRents(deals);
        return deals.size();
    }

    private IngestResult finish(IngestLog logEntry, IngestStatus status,
                                int rowCount, String resultCode, int attempts) {
        logEntry.record(status, rowCount, resultCode, attempts);
        logRepository.save(logEntry);
        return new IngestResult(status, rowCount, resultCode);
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt) * 100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
