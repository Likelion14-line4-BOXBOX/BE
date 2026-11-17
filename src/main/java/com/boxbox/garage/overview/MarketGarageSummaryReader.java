package com.boxbox.garage.overview;

import com.boxbox.garage.overview.dto.GarageMarketSummary;
import com.boxbox.market.portfolio.MarketPortfolioService;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class MarketGarageSummaryReader implements GarageMarketSummaryReader {
    private final MarketPortfolioService portfolio;

    @Override
    public Optional<GarageMarketSummary> find(long userId) {
        try {
            return portfolio.summary(userId, Instant.now()).map(value -> new GarageMarketSummary(
                    value.totalAssets(), value.seasonReturnRate(), value.seasonRank(), value.snapshotAt()));
        } catch (DataAccessException error) {
            log.warn("개러지 자산 요약을 조회하지 못했습니다. userId={}", userId, error);
            return Optional.empty();
        }
    }
}
