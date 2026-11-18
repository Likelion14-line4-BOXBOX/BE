package com.boxbox.garage.stamp;

import com.boxbox.garage.stamp.dto.RaceStampsResponse.Item;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class RaceStampRepository {
    private final JdbcTemplate jdbc;

    boolean authenticated(String loginId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE login_id=? AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED')",
                Long.class, loginId) > 0;
    }

    boolean userExists(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED')",
                Long.class, userId) > 0;
    }

    List<Item> items(long userId, int season) {
        // chat_rooms.race_id UNIQUE와 참여 PK(room_id,user_id)로 레이스당 정확히 한 행이다.
        return jdbc.query("""
                SELECT r.id,COALESCE(NULLIF(TRIM(r.name_ko),''),r.name) AS name,r.name AS name_en,r.round_number,p.stamp_recorded_at
                FROM races r LEFT JOIN chat_rooms c ON c.race_id=r.id
                LEFT JOIN chat_participants p ON p.room_id=c.id AND p.user_id=?
                WHERE r.season_year=? ORDER BY r.round_number,r.id
                """, (rs, row) -> {
            LocalDateTime recordedAt = rs.getObject("stamp_recorded_at", LocalDateTime.class);
            return new Item(rs.getString("id"), rs.getString("name"), rs.getString("name_en"), rs.getInt("round_number"),
                    recordedAt == null ? null : "WATCHED", recordedAt == null ? null : "CHAT",
                    recordedAt == null ? null : recordedAt.toInstant(ZoneOffset.UTC));
        }, userId, season);
    }
}
