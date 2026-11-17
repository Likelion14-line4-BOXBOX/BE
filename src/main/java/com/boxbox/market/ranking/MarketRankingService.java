package com.boxbox.market.ranking;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.MarketRoundService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MarketRankingService {
    private final JdbcTemplate jdbc;
    private final int activeSeason;

    MarketRankingService(JdbcTemplate jdbc, @Value("${boxbox.market.preparation.season:2026}") int activeSeason) {
        this.jdbc = jdbc;
        this.activeSeason = activeSeason;
    }

    @Transactional(readOnly = true)
    public Page list(String type, String scope, Integer season, String roundId, String cursor,
                     int size, String loginId, Instant now) {
        int year = season == null ? activeSeason : season;
        boolean round = "ROUND".equals(scope);
        if ((!round && !"SEASON".equals(scope)) || !List.of("ASSET", "ROUND_RETURN", "SEASON_RETURN").contains(type)
                || (round && "SEASON_RETURN".equals(type)) || (!round && "ROUND_RETURN".equals(type))
                || (!round && roundId != null) || size < 1 || size > 100 || year < 1000 || year > 9999) {
            throw ApiException.invalidRequest("랭킹 조회 조건을 확인해 주세요.");
        }

        BigDecimal requested = roundId == null ? null : new BigDecimal(MarketRoundService.parseRoundId(roundId));
        if (requested != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_rounds WHERE id = ? AND season_year = ?", Integer.class,
                requested, year) == 0) {
            throw MarketRoundService.roundNotFound();
        }
        Cursor after = cursor == null ? null : decode(cursor);
        String metric = "ASSET".equals(type) ? "TOTAL_ASSETS" : "RETURN_RATE";
        var where = new StringBuilder("""
                SELECT id, round_id, snapshot_at FROM ranking_snapshots
                WHERE season_year = ? AND scope = ? AND type = ? AND status = 'PUBLISHED' AND published_at <= ?
                """);
        var args = new java.util.ArrayList<Object>(List.of(year, scope, metric,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        if (requested != null) {
            where.append(" AND round_id = ?");
            args.add(requested);
        }
        if (after != null) {
            where.append(" AND id = ?");
            args.add(after.snapshot());
        }
        where.append(" ORDER BY published_at DESC, id DESC LIMIT 1");
        var snapshot = jdbc.query(where.toString(), (row, index) -> new Snapshot(row.getBigDecimal("id"),
                row.getString("round_id"), row.getObject("snapshot_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                args.toArray()).stream().findFirst().orElseThrow(() -> {
                    if (after != null) return ApiException.invalidRequest("랭킹 커서를 확인해 주세요.");
                    return new ApiException(HttpStatus.CONFLICT, "RANKING_NOT_PUBLISHED", "랭킹이 아직 공개되지 않았습니다.");
                });

        String entries = """
                SELECT e.`rank`, e.user_id, u.nickname, u.avatar_asset_id, u.bio,
                       e.total_assets, COALESCE(e.round_pnl, 0) AS pnl, e.return_rate
                FROM ranking_entries e JOIN users u ON u.id = e.user_id
                WHERE e.snapshot_id = ? AND u.deleted_at IS NULL
                """;
        var rows = jdbc.query(entries + """
                AND (e.`rank` > ? OR (e.`rank` = ? AND e.user_id > ?))
                ORDER BY e.`rank`, e.user_id LIMIT ?
                """, (row, index) -> {
                    long rank = row.getLong("rank");
                    return new Item(rank, new User(row.getString("user_id"), row.getString("nickname"),
                            row.getString("avatar_asset_id")), row.getBigDecimal("total_assets").toBigIntegerExact(),
                            row.getBigDecimal("pnl").toBigIntegerExact(), row.getBigDecimal("return_rate"),
                            rank == 1 ? row.getString("bio") : null);
                }, snapshot.id(), after == null ? 0 : after.rank(), after == null ? 0 : after.rank(),
                after == null ? BigDecimal.ZERO : after.user(), size + 1);
        var mine = loginId == null ? null : jdbc.query(entries + " AND u.login_id = ?",
                (row, index) -> new MyRank(row.getLong("rank"), row.getString("nickname"),
                        row.getBigDecimal("total_assets").toBigIntegerExact(), row.getBigDecimal("return_rate")),
                snapshot.id(), loginId).stream().findFirst().orElse(null);
        boolean hasNext = rows.size() > size;
        var items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        String next = hasNext ? encode(snapshot.id(), items.getLast()) : null;
        return new Page(type, snapshot.roundId(), scope, year, snapshot.at(), items, next, hasNext, mine);
    }

    private static String encode(BigDecimal snapshot, Item item) {
        String value = snapshot.toPlainString() + ":" + item.rank() + ":" + item.user().userId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        try {
            if (value.length() > 100) throw new IllegalArgumentException();
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || !parts[0].matches("[1-9][0-9]{0,19}")
                    || !parts[1].matches("[1-9][0-9]{0,9}") || !parts[2].matches("[1-9][0-9]{0,19}")) {
                throw new IllegalArgumentException();
            }
            return new Cursor(new BigDecimal(parts[0]), Long.parseLong(parts[1]), new BigDecimal(parts[2]));
        } catch (IllegalArgumentException error) {
            throw ApiException.invalidRequest("랭킹 커서를 확인해 주세요.");
        }
    }

    private record Cursor(BigDecimal snapshot, long rank, BigDecimal user) {}
    private record Snapshot(BigDecimal id, String roundId, Instant at) {}
    record User(String userId, String nickname, String avatarAssetId) {}
    record Item(long rank, User user, BigInteger totalAssets, BigInteger roundPnl, BigDecimal returnRate, String bio) {}
    record MyRank(long rank, String nickname, BigInteger totalAssets, BigDecimal returnRate) {}
    record Page(String type, String roundId, String scope, int season, Instant snapshotAt, List<Item> items,
                String nextCursor, boolean hasNext, MyRank myRank) {}
}
