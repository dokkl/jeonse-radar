package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.deal.service.ContractTermParser;
import com.jeonseradar.deal.service.DealHasher;
import com.jeonseradar.ingest.parser.FieldConverter;
import com.jeonseradar.ingest.parser.MolitFields;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Component
public class RentDealMapper {

    private static final String RENEWAL_USED = "사용";

    private final ComplexResolver complexResolver;

    public RentDealMapper(ComplexResolver complexResolver) {
        this.complexResolver = complexResolver;
    }

    public RentDeal map(Map<String, String> item) {
        String aptSeq = text(item, MolitFields.APT_SEQ);
        String regionCode = text(item, MolitFields.SGG_CD);
        String legalDong = text(item, MolitFields.UMD_NM);
        String jibun = FieldConverter.toJibun(item);
        String aptName = text(item, MolitFields.APT_NM);
        Integer buildYear = intOrNull(item.get(MolitFields.BUILD_YEAR));

        BigDecimal area = FieldConverter.toArea(item.get(MolitFields.EXCLU_USE_AR));
        int floor = intOrZero(item.get(MolitFields.FLOOR));
        LocalDate dealDate = FieldConverter.toDealDate(item);

        long deposit = FieldConverter.toWon(item.get(MolitFields.DEPOSIT));
        long monthlyRent = FieldConverter.toWon(item.get(MolitFields.MONTHLY_RENT));

        String termRaw = text(item, MolitFields.CONTRACT_TERM);
        var term = ContractTermParser.parse(termRaw, dealDate);

        Long complexId = complexResolver.resolve(
                regionCode, aptSeq, legalDong, jibun, aptName, buildYear);
        String hash = DealHasher.hashRent(
                regionCode, legalDong, jibun, area, floor, dealDate, deposit, monthlyRent);

        return RentDeal.of(
                hash, complexId, aptSeq, regionCode, legalDong, jibun, aptName,
                area, FieldConverter.toAreaBucket(area), floor,
                dealDate, deposit, monthlyRent, buildYear,
                text(item, MolitFields.CONTRACT_TYPE),
                termRaw, term.startYm(), term.endYm(), term.estimated(),
                RENEWAL_USED.equals(text(item, MolitFields.USE_RR_RIGHT)),
                wonOrNull(item.get(MolitFields.PRE_DEPOSIT)),
                wonOrNull(item.get(MolitFields.PRE_MONTHLY_RENT)));
    }

    private String text(Map<String, String> item, String key) {
        String value = item.get(key);
        return value == null ? "" : value.trim();
    }

    /** 종전계약 금액은 신규 계약이면 비어 있다. 0원과 "없음"을 구분해야 하므로 null을 쓴다. */
    private Long wonOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return FieldConverter.toWon(raw);
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
