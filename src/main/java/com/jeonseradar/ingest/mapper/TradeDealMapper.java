package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.TradeDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.deal.service.DealHasher;
import com.jeonseradar.ingest.parser.FieldConverter;
import com.jeonseradar.ingest.parser.MolitFields;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Component
public class TradeDealMapper {

    private final ComplexResolver complexResolver;

    public TradeDealMapper(ComplexResolver complexResolver) {
        this.complexResolver = complexResolver;
    }

    public TradeDeal map(Map<String, String> item) {
        String aptSeq = text(item, MolitFields.APT_SEQ);
        String regionCode = text(item, MolitFields.SGG_CD);
        String legalDong = text(item, MolitFields.UMD_NM);
        String jibun = FieldConverter.toJibun(item);
        String aptName = text(item, MolitFields.APT_NM);
        Integer buildYear = intOrNull(item.get(MolitFields.BUILD_YEAR));

        BigDecimal area = FieldConverter.toArea(item.get(MolitFields.EXCLU_USE_AR));
        int floor = intOrZero(item.get(MolitFields.FLOOR));
        LocalDate dealDate = FieldConverter.toDealDate(item);
        long amount = FieldConverter.toWon(item.get(MolitFields.DEAL_AMOUNT));

        Long complexId = complexResolver.resolve(
                regionCode, aptSeq, legalDong, jibun, aptName, buildYear);
        String hash = DealHasher.hashTrade(regionCode, legalDong, jibun, area, floor, dealDate, amount);

        return TradeDeal.of(
                hash, complexId, aptSeq, regionCode, legalDong, jibun, aptName,
                area, FieldConverter.toAreaBucket(area), floor,
                dealDate, amount, buildYear,
                text(item, MolitFields.DEALING_GBN),
                text(item, MolitFields.ESTATE_AGENT_SGG_NM),
                FieldConverter.toRegisteredDate(item.get(MolitFields.RGST_DATE)),
                text(item, MolitFields.CDEAL_TYPE),
                FieldConverter.toRegisteredDate(item.get(MolitFields.CDEAL_DAY)));
    }

    private String text(Map<String, String> item, String key) {
        String value = item.get(key);
        return value == null ? "" : value.trim();
    }

    private Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intOrZero(String raw) {
        Integer value = intOrNull(raw);
        return value == null ? 0 : value;
    }
}
