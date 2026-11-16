package com.boxbox.market.settlement;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.settlement.dto.MarketSettlementListResponse;
import com.boxbox.market.settlement.dto.MarketSettlementListResponse.Item;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class MarketSettlementService {
    private final MarketSettlementRepository repository;

    MarketSettlementListResponse list(String loginId, String seasonValue, String cursor, String size, Instant now) {
        Integer season = parseSeason(seasonValue);
        if (!size.matches("[1-9][0-9]?|100")) {
            throw ApiException.invalidRequest("size는 1부터 100까지 입력해 주세요.");
        }
        int pageSize = Integer.parseInt(size);
        var boundary = cursor == null ? null : decode(cursor, season);

        if (loginId == null) {
            return new MarketSettlementListResponse(List.of(), null, false);
        }
        var rows = repository.find(loginId, season, boundary, pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        var items = rows.stream().limit(pageSize).map(row -> toItem(row, now)).toList();
        String nextCursor = hasNext ? encode(rows.get(pageSize - 1), season) : null;
        return new MarketSettlementListResponse(items, nextCursor, hasNext);
    }

    private static Item toItem(MarketSettlementRepository.Summary row, Instant now) {
        boolean ready = "PUBLISHED".equals(row.status()) || "COMPLETED".equals(row.status());
        boolean published = ready && row.publishedAt() != null && !row.publishedAt().isAfter(now);
        String status = published ? "PUBLISHED" : switch (row.status()) {
            case "FAILED" -> "FAILED";
            case "PROCESSING", "COMPLETED", "PUBLISHED" -> "PROCESSING";
            default -> "PENDING";
        };

        var startAssets = row.roundStartAssets() == null ? BigInteger.ZERO : row.roundStartAssets();
        return new Item(row.roundId(), row.raceName(), row.roundNumber(), status, startAssets,
                published ? row.endingAssets() : null, published ? row.roundPnl() : null,
                published && startAssets.signum() != 0 ? row.returnRate() : null,
                published ? row.rank() : null, published ? row.settlementProceeds() : null,
                published ? row.publishedAt() : null);
    }

    private static Integer parseSeason(String season) {
        if (season == null) {
            return null;
        }
        if (!season.matches("[1-9][0-9]{3}")) {
            throw ApiException.invalidRequest("season은 네 자리 연도여야 합니다.");
        }
        return Integer.valueOf(season);
    }

    private static String encode(MarketSettlementRepository.Summary last, Integer season) {
        String position = scope(season) + last.season() + ":" + last.roundNumber() + ":" + last.roundId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    private static MarketSettlementRepository.Cursor decode(String cursor, Integer season) {
        if (cursor.length() <= 100) {
            try {
                String prefix = scope(season);
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(prefix)) {
                    String position = decoded.substring(prefix.length());
                    if (position.matches("[1-9][0-9]{3}:[0-9]{1,5}:[1-9][0-9]{0,19}")) {
                        String[] parts = position.split(":");
                        int cursorSeason = Integer.parseInt(parts[0]);
                        var id = new BigInteger(parts[2]);
                        if ((season == null || season == cursorSeason) && id.bitLength() <= 64) {
                            return new MarketSettlementRepository.Cursor(cursorSeason, Integer.parseInt(parts[1]), id);
                        }
                    }
                }
            } catch (IllegalArgumentException exception) {
                throw ApiException.invalidRequest("cursor를 확인해 주세요.");
            }
        }
        throw ApiException.invalidRequest("cursor를 확인해 주세요.");
    }

    private static String scope(Integer season) {
        return (season == null ? "" : season.toString()) + "|";
    }
}
