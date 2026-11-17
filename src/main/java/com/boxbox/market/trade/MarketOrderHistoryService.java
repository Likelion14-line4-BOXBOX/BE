package com.boxbox.market.trade;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.trade.dto.MarketOrderHistoryResponse;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class MarketOrderHistoryService {
    private static final Set<String> TYPES = Set.of("BUY", "SELL", "SETTLEMENT_SELL");
    private final MarketOrderHistoryRepository repository;

    MarketOrderHistoryResponse history(String loginId, String roundId, String type, String cursor, String size) {
        var round = roundId == null ? null : parseId(roundId, "roundId");
        if (type != null && !TYPES.contains(type)) {
            throw ApiException.invalidRequest("type을 확인해 주세요.");
        }
        if (!size.matches("[1-9][0-9]?|100")) {
            throw ApiException.invalidRequest("size는 1부터 100까지 입력해 주세요.");
        }
        int pageSize = Integer.parseInt(size);
        String scope = (round == null ? "" : round.toString()) + "|" + (type == null ? "" : type) + "|";
        var boundary = cursor == null ? null : decode(cursor, scope);
        if (loginId == null) {
            return new MarketOrderHistoryResponse(List.of(), null, false);
        }

        var items = repository.find(loginId, round, type, boundary, pageSize + 1);
        if (items.size() <= pageSize) {
            return new MarketOrderHistoryResponse(items, null, false);
        }
        var last = items.get(pageSize - 1);
        String position = scope + LocalDateTime.ofInstant(last.executedAt(), ZoneOffset.UTC) + "|" + last.orderId();
        String nextCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(position.getBytes(StandardCharsets.UTF_8));
        return new MarketOrderHistoryResponse(List.copyOf(items.subList(0, pageSize)), nextCursor, true);
    }

    private static MarketOrderHistoryRepository.Cursor decode(String cursor, String scope) {
        if (cursor.length() > 160) {
            throw invalidCursor();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (value.startsWith(scope)) {
                String[] position = value.substring(scope.length()).split("\\|", -1);
                if (position.length == 2) {
                    var at = LocalDateTime.parse(position[0]);
                    if (at.getYear() >= 1000 && at.getYear() <= 9999 && at.getNano() % 1000 == 0) {
                        return new MarketOrderHistoryRepository.Cursor(at.toInstant(ZoneOffset.UTC),
                                parseId(position[1], "cursor"));
                    }
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalidCursor();
        }
        throw invalidCursor();
    }

    private static ApiException invalidCursor() {
        return ApiException.invalidRequest("cursor를 확인해 주세요.");
    }

    private static BigInteger parseId(String value, String parameter) {
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw ApiException.invalidRequest(parameter + "를 확인해 주세요.");
        }
        var id = new BigInteger(value);
        if (id.bitLength() > 64) {
            throw ApiException.invalidRequest(parameter + "를 확인해 주세요.");
        }
        return id;
    }
}
