package com.boxbox.garage.profile;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.profile.dto.ProfileResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 공통 users/teams/drivers를 직접 사용한다. 인증·지갑 엔티티를 별도로 복제하지 않는다. */
@Repository
@RequiredArgsConstructor
public class ProfileRepository {
    private final JdbcTemplate jdbc;

    public Optional<ProfileResponse> findActiveProfile(String loginId) {
        return jdbc.query("""
                SELECT u.id, u.nickname, u.bio, u.avatar_asset_id, u.show_market_assets,
                       u.created_at, u.onboarding_completed,
                       t.id AS team_id, t.name AS team_name, t.championship_rank AS team_rank,
                       d.id AS driver_id, d.name AS driver_name, d.car_number,
                       d.championship_rank AS driver_rank, d.team_id AS driver_team_id,
                       dt.name AS driver_team_name
                FROM users u
                LEFT JOIN teams t ON t.id = u.favorite_team_id
                LEFT JOIN drivers d ON d.id = u.favorite_driver_id
                LEFT JOIN teams dt ON dt.id = d.team_id
                WHERE u.login_id = ? AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                """, (rs, row) -> mapProfile(rs), loginId).stream().findFirst();
    }

    public boolean lockActiveUser(String loginId) {
        // 같은 회원의 병렬 부분 수정이 서로의 생략 필드를 덮어쓰지 않도록 먼저 행을 잠근다.
        return !jdbc.query("""
                SELECT id FROM users
                WHERE login_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                FOR UPDATE
                """, (rs, row) -> rs.getLong("id"), loginId).isEmpty();
    }

    public boolean nicknameTaken(String nickname, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname = ? AND id <> ?",
                Long.class, nickname, userId) > 0;
    }

    public boolean teamExists(long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id = ?", Long.class, teamId) > 0;
    }

    public Optional<DriverTeam> findDriverTeam(long driverId) {
        return jdbc.query("SELECT team_id FROM drivers WHERE id = ?",
                (rs, row) -> new DriverTeam(rs.getObject("team_id", Long.class)), driverId)
                .stream().findFirst();
    }

    public record DriverTeam(Long teamId) {}

    public void update(long userId, String nickname, String bio, String avatarAssetId,
                       Long teamId, Long driverId, boolean showMarketAssets) {
        try {
            // 인증·계정 상태·onboarding·지갑 필드는 이 API의 수정 대상이 아니다.
            jdbc.update("""
                    UPDATE users SET nickname = ?, bio = ?, avatar_asset_id = ?, favorite_team_id = ?,
                                     favorite_driver_id = ?, show_market_assets = ?, updated_at = ?
                    WHERE id = ?
                    """, nickname, bio, avatarAssetId, teamId, driverId, showMarketAssets,
                    LocalDateTime.now(ZoneOffset.UTC), userId);
        } catch (DuplicateKeyException exception) {
            // 사전 중복 검사 이후 다른 회원이 같은 닉네임을 저장한 경우도 UNIQUE 제약으로 막는다.
            throw nicknameTaken();
        }
    }

    static ApiException nicknameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
    }

    private ProfileResponse mapProfile(ResultSet rs) throws SQLException {
        String teamId = rs.getString("team_id");
        String driverId = rs.getString("driver_id");
        var team = teamId == null ? null : new ProfileResponse.Team(teamId,
                rs.getString("team_name"), rs.getObject("team_rank", Integer.class));
        var driver = driverId == null ? null : new ProfileResponse.Driver(driverId,
                rs.getString("driver_name"), rs.getObject("car_number", Integer.class),
                rs.getString("driver_team_id"), rs.getString("driver_team_name"),
                rs.getObject("driver_rank", Integer.class));
        return new ProfileResponse(rs.getString("id"), rs.getString("nickname"), rs.getString("bio"),
                rs.getString("avatar_asset_id"), team, driver, rs.getBoolean("show_market_assets"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getBoolean("onboarding_completed"));
    }
}
