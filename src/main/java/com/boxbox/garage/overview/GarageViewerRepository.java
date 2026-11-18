package com.boxbox.garage.overview;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class GarageViewerRepository {
    private final JdbcTemplate jdbc;

    Optional<Long> viewerId(String loginId) {
        return jdbc.query("SELECT id FROM users WHERE login_id=? AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED')",
                (rs, row) -> rs.getLong("id"), loginId).stream().findFirst();
    }
}
