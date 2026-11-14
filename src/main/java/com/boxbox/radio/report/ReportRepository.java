package com.boxbox.radio.report;

import com.boxbox.common.api.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ReportRepository {
    private final JdbcTemplate jdbc;

    long lockReporter(String loginId) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        var reporter = jdbc.query("SELECT id,status FROM users WHERE login_id=? AND deleted_at IS NULL FOR UPDATE",
                (rs, row) -> new Reporter(rs.getLong("id"), rs.getString("status")), loginId)
                .stream().findFirst().orElseThrow(ApiException::unauthorized);
        if ("SUSPENDED".equals(reporter.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REPORTING_RESTRICTED", "신고 접수가 제한된 계정입니다.");
        }
        if (!"ACTIVE".equals(reporter.status())) throw ApiException.unauthorized();
        return reporter.id();
    }

    void lockTarget(ReportInput input) {
        switch (input.targetType()) {
            case POST -> lockPost(input.targetId());
            case COMMENT -> {
                long postId = jdbc.query("SELECT post_id FROM comments WHERE id=?", (rs, row) -> rs.getLong(1), input.targetId())
                        .stream().findFirst().orElseThrow(ReportRepository::targetNotFound);
                lockPost(postId);
                requireRow("SELECT id FROM comments WHERE id=? AND post_id=? AND deleted_at IS NULL FOR UPDATE", input.targetId(), postId);
            }
            case CHAT_MESSAGE -> {
                long roomId = jdbc.query("SELECT room_id FROM chat_messages WHERE id=?", (rs, row) -> rs.getLong(1), input.targetId())
                        .stream().findFirst().orElseThrow(ReportRepository::targetNotFound);
                requireRow("SELECT id FROM chat_rooms WHERE id=? FOR UPDATE", roomId);
                requireRow("SELECT id FROM chat_messages WHERE id=? AND room_id=? AND deleted_at IS NULL FOR UPDATE", input.targetId(), roomId);
            }
        }
    }

    boolean alreadyReported(long reporterId, ReportInput input) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? AND " + column(input.targetType()) + "=? AND reason=?",
                Long.class, reporterId, input.targetId(), input.reason().name()) > 0;
    }

    long insert(long reporterId, ReportInput input, Instant now) {
        var key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO reports(reporter_id,target_type,post_id,comment_id,message_id,reason,description,status,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,'RECEIVED',?,?)
                        """, new String[] {"id"});
                statement.setLong(1, reporterId);
                statement.setString(2, input.targetType().name());
                statement.setObject(3, input.targetType() == ReportInput.TargetType.POST ? input.targetId() : null);
                statement.setObject(4, input.targetType() == ReportInput.TargetType.COMMENT ? input.targetId() : null);
                statement.setObject(5, input.targetType() == ReportInput.TargetType.CHAT_MESSAGE ? input.targetId() : null);
                statement.setString(6, input.reason().name());
                statement.setString(7, input.description());
                statement.setObject(8, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                statement.setObject(9, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                return statement;
            }, key);
        } catch (DuplicateKeyException exception) { throw duplicate(); }
        return key.getKey().longValue();
    }

    private void lockPost(long postId) {
        requireRow("""
                SELECT p.id FROM posts p JOIN boards b ON b.id=p.board_id
                WHERE p.id=? AND p.deleted_at IS NULL AND b.is_active=TRUE FOR UPDATE
                """, postId);
    }

    private void requireRow(String sql, Object... args) {
        if (jdbc.query(sql, (rs, row) -> rs.getLong(1), args).isEmpty()) throw targetNotFound();
    }

    private static String column(ReportInput.TargetType target) {
        return switch (target) { case POST -> "post_id"; case COMMENT -> "comment_id"; case CHAT_MESSAGE -> "message_id"; };
    }
    static ApiException duplicate() { return new ApiException(HttpStatus.CONFLICT, "ALREADY_REPORTED", "같은 대상과 사유로 이미 신고했습니다."); }
    private static ApiException targetNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "REPORT_TARGET_NOT_FOUND", "신고 대상을 찾을 수 없습니다."); }
    private record Reporter(long id, String status) { }
}
