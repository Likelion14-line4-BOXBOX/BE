package com.boxbox.common.reference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReferenceDataService {
    private final JdbcTemplate jdbc;
    private final int season;
    private final String referenceVersion;

    ReferenceDataService(JdbcTemplate jdbc,
                         @Value("${boxbox.reference-data.season}") int season,
                         @Value("${boxbox.reference-data.version}") String referenceVersion) {
        this.jdbc = jdbc;
        this.season = season;
        this.referenceVersion = referenceVersion;
    }

    @Transactional(readOnly = true)
    public ReferenceData getReferenceData() {
        var teams = jdbc.query("""
                SELECT id, code, COALESCE(NULLIF(TRIM(name_ko),''),name) AS name, name AS name_en, description, display_order,
                       CASE WHEN stats_season_year = ? THEN championship_rank END AS championship_rank,
                       CASE WHEN stats_season_year = ? THEN championship_points END AS championship_points,
                       CASE WHEN stats_season_year = ? THEN stats_as_of END AS stats_as_of
                FROM teams
                ORDER BY display_order, id
                """, (row, index) -> new ReferenceData.Team(row.getString("id"), row.getString("code"),
                row.getString("name"), row.getString("name_en"), row.getString("description"), row.getInt("display_order"),
                row.getObject("championship_rank", Integer.class), row.getBigDecimal("championship_points"),
                statsAsOf(row)), season, season, season);
        var drivers = jdbc.query("""
                SELECT d.id, d.code, COALESCE(NULLIF(TRIM(d.name_ko),''),d.name) AS name, d.name AS name_en, d.car_number, d.team_id, d.description,
                       CASE WHEN d.stats_season_year = ? THEN d.championship_rank END AS championship_rank,
                       CASE WHEN d.stats_season_year = ? THEN d.championship_points END AS championship_points,
                       CASE WHEN d.stats_season_year = ? THEN d.stats_as_of END AS stats_as_of
                FROM drivers d
                JOIN teams t ON t.id = d.team_id
                WHERE d.car_number IS NOT NULL
                ORDER BY t.display_order, t.id, d.car_number, d.id
                """, (row, index) -> new ReferenceData.Driver(row.getString("id"), row.getString("code"),
                row.getString("name"), row.getString("name_en"), row.getInt("car_number"), row.getString("team_id"),
                row.getString("description"), row.getObject("championship_rank", Integer.class),
                row.getBigDecimal("championship_points"), statsAsOf(row)), season, season, season);
        return new ReferenceData(referenceVersion, season, teams, drivers);
    }

    private static Instant statsAsOf(ResultSet row) throws SQLException {
        var timestamp = row.getObject("stats_as_of", LocalDateTime.class);
        return timestamp == null ? null : timestamp.toInstant(ZoneOffset.UTC);
    }
}
