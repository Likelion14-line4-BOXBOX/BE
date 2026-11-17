package com.boxbox.garage.activity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MyPostsRepository {
    private final JdbcTemplate jdbc;

    Optional<Long> viewer(String loginId) {
        return jdbc.query("SELECT id FROM users WHERE login_id=? AND status='ACTIVE' AND deleted_at IS NULL",
                (rs, row) -> rs.getLong(1), loginId).stream().findFirst();
    }

    long maxPostId() { return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM posts", Long.class); }

    List<Activity> page(long viewer, MyPostsRequest request, Instant anchor, long maxPostId, MyPostsCursor cursor) {
        boolean authored = request.filter() == MyPostsRequest.Filter.AUTHORED;
        String time = authored ? "p.created_at" : "r.created_at";
        String relation = authored ? "" : " JOIN post_reactions r ON r.post_id=p.id AND r.user_id=:viewer AND r.type=:type ";
        String own = authored ? " AND p.author_id=:viewer " : "";
        String after = cursor == null ? "" : " AND (" + time + "<:lastTime OR (" + time + "=:lastTime AND p.id<:lastId)) ";
        var params = new MapSqlParameterSource().addValue("viewer", viewer).addValue("type", request.reactionType())
                .addValue("anchor", utc(anchor)).addValue("maxPostId", maxPostId).addValue("limit", request.size() + 1);
        if (cursor != null) params.addValue("lastTime", utc(cursor.activityAt())).addValue("lastId", cursor.postId());
        return new NamedParameterJdbcTemplate(jdbc).query("SELECT p.id," + time + " AS activity_at "
                + " FROM posts p JOIN boards b ON b.id=p.board_id " + relation
                + " WHERE p.deleted_at IS NULL AND b.is_active=TRUE AND p.id<=:maxPostId AND " + time + "<=:anchor "
                + own + after + " ORDER BY " + time + " DESC,p.id DESC LIMIT :limit", params,
                (rs, row) -> new Activity(rs.getLong("id"), rs.getObject("activity_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    record Activity(long postId, Instant activityAt) { }
}
