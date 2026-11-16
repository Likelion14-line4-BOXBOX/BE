package com.boxbox.market.round;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketStockRepository {
    private final NamedParameterJdbcTemplate jdbc;

    List<StockRow> findByRound(BigInteger roundId, int season, BigDecimal teamId) {
        var params = new MapSqlParameterSource()
                .addValue("roundId", new BigDecimal(roundId))
                .addValue("season", season);
        String sql = """
                SELECT s.id, s.driver_id, d.name AS driver_name, d.car_number, s.team_id, t.name AS team_name,
                       s.base_rank, s.starting_price, s.current_price, s.price_version,
                       s.updated_at, s.total_buy_quantity
                FROM market_stocks s
                JOIN drivers d ON d.id = s.driver_id
                JOIN teams t ON t.id = s.team_id
                WHERE s.round_id = :roundId AND s.season_year = :season
                """;
        if (teamId != null) {
            sql += " AND s.team_id = :teamId";
            params.addValue("teamId", teamId);
        }
        return jdbc.query(sql, params, (row, index) -> new StockRow(
                row.getBigDecimal("id").toBigIntegerExact(),
                row.getString("driver_id"), row.getString("driver_name"), row.getObject("car_number", Integer.class),
                row.getString("team_id"), row.getString("team_name"), row.getInt("base_rank"),
                row.getBigDecimal("starting_price").toBigIntegerExact(),
                row.getBigDecimal("current_price").toBigIntegerExact(),
                row.getBigDecimal("price_version").toBigIntegerExact(),
                row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getBigDecimal("total_buy_quantity").toBigIntegerExact()));
    }

    record StockRow(BigInteger id, String driverId, String driverName, Integer number, String teamId, String teamName,
                    int baseRank, BigInteger startingPrice, BigInteger currentPrice, BigInteger priceVersion,
                    Instant updatedAt, BigInteger totalBuyQuantity) { }
}
