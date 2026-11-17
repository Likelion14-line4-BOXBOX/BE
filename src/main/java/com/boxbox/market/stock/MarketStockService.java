package com.boxbox.market.stock;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.settlement.MarketSettlementPrice;
import com.boxbox.market.round.MarketRoundRepository;
import com.boxbox.market.round.MarketRoundService;
import com.boxbox.market.stock.dto.MarketStockResponses;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class MarketStockService {
    private static final int LAST_FINISH_RANK = 22;
    private static final List<Integer> DEFAULT_RESULT_BONUSES = List.of(8, 6, 5, 3, 3, 1, 1, 1, 1, 1);
    private static final Comparator<SortKey> ORDER =
            Comparator.comparing(SortKey::value).thenComparing(SortKey::id);

    private final MarketRoundRepository rounds;
    private final MarketStockRepository stocks;
    private final JsonMapper json;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarketStockResponses.Page list(String roundId, String teamId, String sortValue,
                                          String cursor, String sizeValue, Instant now) {
        BigInteger id = MarketRoundService.parseRoundId(roundId);
        BigDecimal team = parseTeamId(teamId);
        Sort sort = parseSort(sortValue);
        int size = parseSize(sizeValue);
        String cursorScope = roundId + ":" + (teamId == null ? "" : teamId) + ":" + sort + ":";
        SortKey after = parseCursor(cursor, cursorScope);

        var round = rounds.findById(id).orElseThrow(MarketRoundService::roundNotFound).round();
        if (!isPublished(round, now)) {
            return new MarketStockResponses.Page(List.of(), null, false);
        }
        boolean tradable = isTradable(round, now);
        var page = stocks.findByRound(id, round.season(), team).stream()
                .map(row -> {
                    var item = toItem(row, tradable);
                    return new Entry(new SortKey(sort.key(item), row.id()), item);
                })
                .filter(entry -> after == null || ORDER.compare(entry.key(), after) > 0)
                .sorted(Comparator.comparing(Entry::key, ORDER))
                .limit(size + 1L)
                .toList();
        boolean hasNext = page.size() > size;
        String nextCursor = hasNext ? encodeCursor(cursorScope, page.get(size - 1).key()) : null;
        return new MarketStockResponses.Page(page.stream().limit(size).map(Entry::item).toList(), nextCursor, hasNext);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarketStockResponses.Detail detail(String roundId, String driverId, Instant now) {
        BigInteger roundKey = stockLookupId(roundId);
        BigInteger driverKey = stockLookupId(driverId);
        var detail = rounds.findById(roundKey).orElseThrow(MarketStockService::stockNotFound);
        var round = detail.round();
        if (!isPublished(round, now)) {
            throw stockNotFound();
        }
        var row = stocks.findByDriver(roundKey, round.season(), driverKey)
                .orElseThrow(MarketStockService::stockNotFound);
        var item = toItem(row, isTradable(round, now));
        return new MarketStockResponses.Detail(item, settlementScenarios(item, detail.ruleSnapshot()));
    }

    private List<MarketStockResponses.SettlementScenario> settlementScenarios(
            MarketStockResponses.Item item, String snapshot) {
        var bonuses = json.readTree(snapshot).path("resultBonusByRank");
        if (!bonuses.isMissingNode() && (!bonuses.isArray() || bonuses.size() > 65_535)) {
            throw invalidSettlementRules();
        }
        int baseRank = item.baseRank();
        if (baseRank < 1 || baseRank > LAST_FINISH_RANK) {
            throw invalidSettlementRules();
        }

        List<Integer> finishRanks;
        if (baseRank <= 3) {
            finishRanks = List.of(1, 2, 3, 6);
        } else if (baseRank <= 19) {
            finishRanks = List.of(baseRank - 3, baseRank - 1, baseRank, baseRank + 3);
        } else {
            finishRanks = List.of(baseRank - 3, baseRank - 2, baseRank - 1, baseRank);
        }

        var scenarios = new ArrayList<MarketStockResponses.SettlementScenario>(5);
        for (int finishRank : finishRanks) {
            int index = finishRank - 1;
            int resultBonus = index < DEFAULT_RESULT_BONUSES.size() ? DEFAULT_RESULT_BONUSES.get(index) : 0;
            if (index < bonuses.size()) {
                var bonus = bonuses.get(index);
                if (!bonus.isIntegralNumber() || !bonus.canConvertToInt() || bonus.intValue() < 0) {
                    throw invalidSettlementRules();
                }
                resultBonus = bonus.intValue();
            }
            scenarios.add(scenario(finishRank,
                    MarketSettlementPrice.calculate(item.startingPrice(), item.baseRank(), finishRank, resultBonus)));
        }
        scenarios.add(scenario(null, MarketSettlementPrice.retirement(item.startingPrice())));
        return List.copyOf(scenarios);
    }

    private static MarketStockResponses.SettlementScenario scenario(
            Integer finishRank, MarketSettlementPrice settlement) {
        return new MarketStockResponses.SettlementScenario(finishRank, settlement.positionScore(),
                settlement.resultBonus(), settlement.finalScore(), settlement.price());
    }

    private static ApiException invalidSettlementRules() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "라운드 정산 규칙을 읽을 수 없습니다.");
    }

    public static BigInteger stockLookupId(String value) {
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw stockNotFound();
        }
        var id = new BigInteger(value);
        if (id.bitLength() > 64) {
            throw stockNotFound();
        }
        return id;
    }

    public static ApiException stockNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "마켓 종목을 찾을 수 없습니다.");
    }

    static boolean isPublished(MarketRoundRepository.RoundRow round, Instant now) {
        return round.openAt() != null && !now.isBefore(round.openAt()) && !"SCHEDULED".equals(round.status());
    }

    private static boolean isTradable(MarketRoundRepository.RoundRow round, Instant now) {
        return "OPEN".equals(round.status()) && round.closeAt() != null && now.isBefore(round.closeAt());
    }

    private static MarketStockResponses.Item toItem(MarketStockRepository.StockRow row, boolean tradable) {
        if (row.number() == null || row.startingPrice().signum() <= 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                    "종목 기준 정보를 읽을 수 없습니다.");
        }
        var driver = new MarketStockResponses.Driver(row.driverId(), row.driverName(), row.number(),
                row.teamId(), row.teamName());
        BigInteger change = row.currentPrice().subtract(row.startingPrice());
        BigDecimal rate = new BigDecimal(change).movePointRight(2)
                .divide(new BigDecimal(row.startingPrice()), 8, RoundingMode.HALF_UP).stripTrailingZeros();
        return new MarketStockResponses.Item(driver, row.baseRank(), row.startingPrice(), row.currentPrice(),
                change, rate, row.priceVersion(), tradable, row.updatedAt(), row.totalBuyQuantity());
    }

    private static BigDecimal parseTeamId(String teamId) {
        if (teamId == null) {
            return null;
        }
        if (!teamId.matches("[1-9][0-9]{0,19}")
                || new BigInteger(teamId).bitLength() > 64) {
            throw ApiException.invalidRequest("teamId를 확인해 주세요.");
        }
        return new BigDecimal(teamId);
    }

    private static Sort parseSort(String sort) {
        try {
            return Sort.valueOf(sort);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest("sort를 확인해 주세요.");
        }
    }

    static int parseSize(String size) {
        if (!size.matches("[1-9][0-9]?|100")) {
            throw ApiException.invalidRequest("size는 1부터 100까지 입력해 주세요.");
        }
        return Integer.parseInt(size);
    }

    private static String encodeCursor(String scope, SortKey key) {
        String value = scope + key.value().toPlainString() + ":" + key.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SortKey parseCursor(String cursor, String scope) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.length() <= 200) {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(scope)) {
                    String position = decoded.substring(scope.length());
                    if (position.matches("-?[0-9]{1,24}(\\.[0-9]{1,8})?:[1-9][0-9]{0,19}")) {
                        String[] parts = position.split(":");
                        return new SortKey(new BigDecimal(parts[0]), new BigInteger(parts[1]));
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest("cursor를 확인해 주세요.");
        }
        throw ApiException.invalidRequest("cursor를 확인해 주세요.");
    }

    private enum Sort {
        BASE_RANK, PRICE_DESC, CHANGE_RATE_DESC;

        BigDecimal key(MarketStockResponses.Item item) {
            return switch (this) {
                case BASE_RANK -> BigDecimal.valueOf(item.baseRank());
                case PRICE_DESC -> new BigDecimal(item.currentPrice()).negate();
                case CHANGE_RATE_DESC -> item.changeRate().negate();
            };
        }
    }

    private record SortKey(BigDecimal value, BigInteger id) { }

    private record Entry(SortKey key, MarketStockResponses.Item item) { }
}
