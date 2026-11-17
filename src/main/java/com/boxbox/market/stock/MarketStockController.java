package com.boxbox.market.stock;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.stock.dto.MarketStockResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketStockController {
    private final MarketStockService stockService;
    private final MarketPriceService priceService;

    @GetMapping("/api/market/rounds/{roundId}/stocks")
    ApiResponse<MarketStockResponses.Page> stocks(
            @PathVariable("roundId") String roundId,
            @RequestParam(name = "teamId", required = false) String teamId,
            @RequestParam(name = "sort", defaultValue = "BASE_RANK") String sort,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") String size) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(stockService.list(roundId, teamId, sort, cursor, size, meta.serverTime()), meta);
    }

    @GetMapping("/api/market/rounds/{roundId}/stocks/{driverId}")
    ApiResponse<MarketStockResponses.Detail> stock(
            @PathVariable("roundId") String roundId, @PathVariable("driverId") String driverId) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(stockService.detail(roundId, driverId, meta.serverTime()), meta);
    }

    @GetMapping("/api/market/rounds/{roundId}/stocks/{driverId}/prices")
    ApiResponse<MarketStockResponses.Prices> prices(
            @PathVariable("roundId") String roundId, @PathVariable("driverId") String driverId,
            @RequestParam(name = "interval", defaultValue = "1m") String interval,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") String size) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(priceService.prices(roundId, driverId, interval, from, to, cursor, size,
                meta.serverTime()), meta);
    }
}
