package com.boxbox.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class AuthRepository {
    private final JdbcTemplate jdbc;
    record User(long id, String loginId, String passwordHash, String status, boolean deleted) {
        boolean active() { return "ACTIVE".equals(status) && !deleted; }
        boolean readable() { return ("ACTIVE".equals(status) || "SUSPENDED".equals(status)) && !deleted; }
        @Override public String toString() { return "User[id=" + id + "]"; }
    }
    record Session(long id, long userId, String family, Instant expiresAt, boolean rotated, boolean revoked) {}

    Optional<User> byLogin(String loginId) {
        return jdbc.query("SELECT id,login_id,password_hash,status,deleted_at FROM users WHERE login_id=?", this::user, loginId).stream().findFirst();
    }
    Optional<User> byId(long id, boolean lock) {
        return jdbc.query("SELECT id,login_id,password_hash,status,deleted_at FROM users WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::user, id).stream().findFirst();
    }
    boolean nicknameExists(String nickname) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname=?", Long.class, nickname) > 0;
    }
    long createUser(AuthInput.Signup input, String hash, long coins, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO users(login_id,password_hash,nickname,status,coin_balance,created_at,updated_at)
                    VALUES(?,?,?,'ACTIVE',?,?,?)
                    """, new String[]{"id"});
            statement.setString(1, input.loginId());
            statement.setString(2, hash);
            statement.setString(3, input.nickname());
            statement.setLong(4, coins);
            statement.setObject(5, utc(now));
            statement.setObject(6, utc(now));
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO coin_ledger(user_id,event_key,entry_type,amount_delta,balance_after,created_at)
                VALUES(?,?,'INITIAL_GRANT',?,?,?)
                """, id, "INITIAL_GRANT", coins, coins, utc(now));
        return id;
    }
    Optional<Session> session(byte[] hash, boolean lock) {
        return jdbc.query("""
                SELECT id,user_id,family_id,expires_at,rotated_at,revoked_at FROM auth_sessions WHERE refresh_token_hash=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Session(rs.getLong("id"), rs.getLong("user_id"), rs.getString("family_id"),
                rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getObject("rotated_at") != null, rs.getObject("revoked_at") != null), hash).stream().findFirst();
    }
    long createSession(long userId, String family, byte[] hash, Instant expires, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO auth_sessions(user_id,family_id,refresh_token_hash,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    """, new String[]{"id"});
            statement.setLong(1, userId);
            statement.setString(2, family);
            statement.setBytes(3, hash);
            statement.setObject(4, utc(expires));
            statement.setObject(5, utc(now));
            statement.setObject(6, utc(now));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
    void rotate(long id, long replacement, Instant now) {
        jdbc.update("UPDATE auth_sessions SET rotated_at=?,replaced_by_id=?,updated_at=? WHERE id=?", utc(now), replacement, utc(now), id);
    }
    void revokeFamily(long userId, String family, Instant now) {
        jdbc.update("UPDATE auth_sessions SET revoked_at=?,updated_at=? WHERE user_id=? AND family_id=? AND revoked_at IS NULL", utc(now), utc(now), userId, family);
    }
    private User user(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("login_id"), rs.getString("password_hash"), rs.getString("status"), rs.getObject("deleted_at") != null);
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
