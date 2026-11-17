package com.boxbox.market.portfolio;

import com.boxbox.market.portfolio.dto.MarketPortfolioResponse;
import com.boxbox.market.portfolio.dto.MarketPortfolioResponse.Position;
import com.boxbox.market.round.MarketRoundRepository;
import com.boxbox.market.round.MarketRoundService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketPortfolioService {
    private final MarketPortfolioRepository repository;
    private final MarketRoundService roundService;
    private final MarketRoundRepository rounds;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarketPortfolioResponse portfolio(String loginId, String requestedRound, Instant now) {
        var current = requestedRound == null ? roundService.current(now).round() : null;
        String roundId = requestedRound != null ? requestedRound : current == null ? null : current.roundId();
        BigInteger id = roundId == null ? null : MarketRoundService.parseRoundId(roundId);
        var round = id == null ? null : rounds.findById(id).orElseThrow(MarketRoundService::roundNotFound).round();

        if (loginId == null) {
            return empty(roundId, BigInteger.ZERO, now);
        }
        var account = repository.account(loginId);
        if (round == null) {
            return empty(null, account.cash(), now);
        }

        var positions = repository.positions(account.id(), id).stream().map(MarketPortfolioService::position).toList();
        var stockValue = positions.stream().map(Position::valuation).reduce(BigInteger.ZERO, BigInteger::add);
        var unrealized = positions.stream().map(Position::unrealizedPnl).reduce(BigInteger.ZERO, BigInteger::add);
        var settlement = repository.settlement(account.id(), id, now);
        var starting = settlement.startingAssets() != null
                ? settlement.startingAssets() : repository.openingBalance(account.id(), round.openAt(), now);
        var realized = repository.realizedSales(account.id(), id);
        if (settlement.pnl() != null) {
            realized = realized.add(settlement.pnl());
        }
        var assets = assets(account, round.season(), stockValue, now);
        return new MarketPortfolioResponse(roundId, account.cash(), stockValue, assets.totalAssets(),
                starting, realized, unrealized, percent(realized.add(unrealized), starting),
                assets.seasonStartAssets(), assets.seasonReturnRate(), assets.seasonRank(), positions, now);
    }

    /** 공개 여부를 검증한 호출자가 대상 ID로 사용하는 내부 조회다. HTTP 인증 주체를 바꾸지 않는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AssetSummary> summary(long userId, Instant now) {
        return repository.account(userId).map(account -> {
            var current = roundService.current(now).round();
            if (current == null) return new AssetSummary(account.cash(), null, null, null, now);
            var stockValue = repository.stockValue(account.id(), MarketRoundService.parseRoundId(current.roundId()));
            return assets(account, current.season(), stockValue, now);
        });
    }

    private AssetSummary assets(MarketPortfolioRepository.Account account, int season, BigInteger stockValue, Instant now) {
        var totalAssets = account.cash().add(stockValue);
        var starting = repository.seasonStartingAssets(account.id(), season);
        var rate = starting == null ? null : percent(totalAssets.subtract(starting), starting);
        return new AssetSummary(totalAssets, starting, rate, repository.seasonRank(account.id(), season, now), now);
    }

    /** 자산은 현재 평가값, 순위는 평가 시각까지 공개된 최근 시즌 총자산 순위다. */
    public record AssetSummary(BigInteger totalAssets, BigInteger seasonStartAssets,
                               BigDecimal seasonReturnRate, Long seasonRank, Instant snapshotAt) { }

    private static Position position(MarketPortfolioRepository.Holding holding) {
        var valuation = holding.price().multiply(holding.quantity());
        var pnl = valuation.subtract(holding.cost());
        var average = new BigDecimal(holding.cost()).divide(new BigDecimal(holding.quantity()), 8, RoundingMode.HALF_UP);
        return new Position(holding.driver(), holding.quantity(), holding.cost(), average,
                holding.price(), valuation, holding.baseRank(), percent(pnl, holding.cost()), pnl);
    }

    private static MarketPortfolioResponse empty(String roundId, BigInteger cash, Instant now) {
        var zero = BigInteger.ZERO;
        return new MarketPortfolioResponse(roundId, cash, zero, cash, zero, zero, zero, null,
                null, null, null, List.of(), now);
    }

    private static BigDecimal percent(BigInteger numerator, BigInteger denominator) {
        return denominator.signum() == 0 ? null : new BigDecimal(numerator).movePointRight(2)
                .divide(new BigDecimal(denominator), 8, RoundingMode.HALF_UP);
    }
}
