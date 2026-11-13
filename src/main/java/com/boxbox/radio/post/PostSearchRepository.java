package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import com.boxbox.radio.post.dto.PostDetailResponse.Tag;
import com.boxbox.radio.post.dto.PostSummaryResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PostSearchRepository {
    private final JdbcTemplate jdbc;
    private final PostMediaUrl mediaUrl;

    private static final String FROM = """
            FROM posts p JOIN boards b ON b.id=p.board_id
            LEFT JOIN board_tags t ON t.id=p.tag_id AND t.board_id=p.board_id
            """;
    private static final String SELECT = """
            SELECT p.id,p.board_id,p.title,p.content,p.view_count,p.created_at,
                b.name AS board_name,t.id AS tag_id,t.name AS tag_name,
                CASE WHEN t.id IS NOT NULL AND t.is_active=FALSE THEN TRUE ELSE FALSE END AS archived,
                u.id AS author_id,u.nickname,u.avatar_asset_id,u.status AS author_status,u.deleted_at AS author_deleted,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.type='LIKE') AS like_count,
                (SELECT COUNT(*) FROM comments c WHERE c.post_id=p.id AND c.deleted_at IS NULL) AS comment_count,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.type='BOOKMARK') AS bookmark_count,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.user_id=:viewer AND r.type='LIKE') AS liked,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.user_id=:viewer AND r.type='BOOKMARK') AS bookmarked,
                (SELECT m.object_key FROM media_files m WHERE m.post_id=p.id AND m.status='ATTACHED'
                    AND m.deleted_at IS NULL ORDER BY m.position,m.id LIMIT 1) AS thumbnail_key
            """;

    String validate(PostSearchFilter filter) {
        String boardType = null;
        if (filter.boardId() != null) {
            var boards = jdbc.query("SELECT type FROM boards WHERE id=? AND is_active=TRUE",
                    (rs, row) -> rs.getString(1), filter.boardId());
            if (boards.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시판을 찾을 수 없습니다.");
            boardType = boards.getFirst();
        }
        if (filter.tagId() != null) {
            var boards = jdbc.query("SELECT board_id FROM board_tags WHERE id=?", (rs, row) -> rs.getLong(1), filter.tagId());
            if (boards.isEmpty() || (filter.boardId() != null && !filter.boardId().equals(boards.getFirst()))) {
                throw ApiException.invalidRequest("검색 범위에 속하는 태그를 선택해 주세요.");
            }
        }
        return boardType;
    }

    long maxId() { return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM posts", Long.class); }

    long total(PostSearchFilter filter, Instant anchor, long maxId) {
        return named().queryForObject("SELECT COUNT(*) " + FROM + where(filter), params(filter, anchor, maxId, null, null), Long.class);
    }

    List<Row> page(PostSearchFilter filter, Instant anchor, long maxId, PostSearchCursor cursor, Long viewer) {
        String after = cursor == null ? "" : after(filter.sort());
        return named().query("SELECT s.* FROM (" + SELECT + FROM + " JOIN users u ON u.id=p.author_id "
                        + where(filter) + ") s " + after + order(filter.sort()) + " LIMIT :limit",
                params(filter, anchor, maxId, cursor, viewer), (rs, index) -> map(rs, viewer, index < filter.size()));
    }

    PostSummaryResponse popular(PostSearchFilter filter, Instant anchor, long maxId, Long viewer) {
        return named().query("SELECT s.* FROM (" + SELECT + FROM + " JOIN users u ON u.id=p.author_id "
                        + where(filter) + ") s ORDER BY s.like_count DESC,s.created_at DESC,s.id DESC LIMIT 1",
                params(filter, anchor, maxId, null, viewer), (rs, index) -> map(rs, viewer, true).summary()).stream().findFirst().orElse(null);
    }

    private String where(PostSearchFilter filter) {
        String sql = " WHERE p.deleted_at IS NULL AND b.is_active=TRUE AND p.created_at<=:anchor AND p.id<=:maxId ";
        sql += filter.scope() == PostSearchFilter.Scope.ARCHIVE
                ? " AND t.id IS NOT NULL AND t.is_active=FALSE "
                : " AND (t.id IS NULL OR t.is_active=TRUE) ";
        if (filter.boardId() != null) sql += " AND p.board_id=:board ";
        if (filter.tagId() != null) sql += " AND p.tag_id=:tag ";
        if (filter.period() != PostSearchFilter.Period.ALL) sql += " AND p.created_at>=:since ";
        if (filter.query() != null) sql += """
                AND (LOWER(p.title) LIKE :query ESCAPE '!' OR LOWER(p.content) LIKE :query ESCAPE '!'
                     OR LOWER(t.name) LIKE :query ESCAPE '!')
                """;
        return sql;
    }

    private MapSqlParameterSource params(PostSearchFilter filter, Instant anchor, long maxId, PostSearchCursor cursor, Long viewer) {
        var params = new MapSqlParameterSource().addValue("board", filter.boardId()).addValue("tag", filter.tagId())
                .addValue("viewer", viewer).addValue("anchor", utc(anchor)).addValue("maxId", maxId).addValue("limit", filter.size() + 1);
        if (filter.period() != PostSearchFilter.Period.ALL) params.addValue("since", utc(filter.since(anchor)));
        if (filter.query() != null) params.addValue("query", "%" + filter.query().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        if (cursor != null) params.addValue("lastTime", utc(cursor.createdAt())).addValue("lastId", cursor.id())
                .addValue("score", cursor.score());
        return params;
    }

    private String order(PostSearchFilter.Sort sort) {
        return switch (sort) {
            case LATEST -> " ORDER BY s.created_at DESC,s.id DESC ";
            case OLDEST -> " ORDER BY s.created_at ASC,s.id ASC ";
            case POPULAR -> " ORDER BY s.like_count DESC,s.created_at DESC,s.id DESC ";
            case COMMENTS -> " ORDER BY s.comment_count DESC,s.created_at DESC,s.id DESC ";
        };
    }

    private String after(PostSearchFilter.Sort sort) {
        String time = sort == PostSearchFilter.Sort.OLDEST
                ? "(s.created_at>:lastTime OR (s.created_at=:lastTime AND s.id>:lastId))"
                : "(s.created_at<:lastTime OR (s.created_at=:lastTime AND s.id<:lastId))";
        return switch (sort) {
            case LATEST, OLDEST -> " WHERE " + time;
            case POPULAR -> " WHERE (s.like_count<:score OR (s.like_count=:score AND " + time + "))";
            case COMMENTS -> " WHERE (s.comment_count<:score OR (s.comment_count=:score AND " + time + "))";
        };
    }

    private Row map(ResultSet rs, Long viewer, boolean includeThumbnail) throws SQLException {
        boolean withdrawn = rs.getObject("author_deleted") != null || "WITHDRAWN".equals(rs.getString("author_status"));
        var author = new Author(rs.getString("author_id"), withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"),
                withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
        String tagId = rs.getString("tag_id");
        List<Tag> tags = tagId == null ? List.of() : List.of(new Tag(tagId, rs.getString("tag_name")));
        String key = rs.getString("thumbnail_key");
        String content = rs.getString("content");
        String preview = content.substring(0, content.offsetByCodePoints(0, Math.min(100, content.codePointCount(0, content.length()))));
        var summary = new PostSummaryResponse(rs.getString("id"), rs.getString("board_id"), rs.getString("title"), preview,
                author, tags, key == null || !includeThumbnail ? null : mediaUrl.resolve(key), rs.getLong("like_count"), rs.getLong("comment_count"),
                rs.getLong("bookmark_count"), rs.getLong("view_count"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                viewer == null ? null : rs.getLong("liked") > 0, viewer == null ? null : rs.getLong("bookmarked") > 0);
        return new Row(summary, rs.getString("board_name"), rs.getBoolean("archived"));
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(jdbc); }
    record Row(PostSummaryResponse summary, String boardName, boolean archived) { }
}
