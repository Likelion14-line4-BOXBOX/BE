package com.boxbox.radio.post;

import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import com.boxbox.radio.post.dto.PostDetailResponse.Tag;
import com.boxbox.radio.post.dto.PostSummaryResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 목록용 읽기 전용 조합이다. 상세 조회나 조회수 변경을 호출하지 않는다. */
@Component
@RequiredArgsConstructor
public class PostSummaryReader {
    private final JdbcTemplate jdbc;
    private final PostMediaUrl mediaUrl;

    static final String SELECT = """
            SELECT p.id,p.board_id,p.title,p.content,p.view_count,p.created_at,
                t.id AS tag_id,t.name AS tag_name,
                u.id AS author_id,u.nickname,u.avatar_asset_id,u.status AS author_status,u.deleted_at AS author_deleted,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.type='LIKE') AS like_count,
                (SELECT COUNT(*) FROM comments c WHERE c.post_id=p.id AND c.deleted_at IS NULL) AS comment_count,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.type='BOOKMARK') AS bookmark_count,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.user_id=:viewer AND r.type='LIKE') AS liked,
                (SELECT COUNT(*) FROM post_reactions r WHERE r.post_id=p.id AND r.user_id=:viewer AND r.type='BOOKMARK') AS bookmarked,
                (SELECT m.object_key FROM media_files m WHERE m.post_id=p.id AND m.status='ATTACHED'
                    AND m.deleted_at IS NULL ORDER BY m.position,m.id LIMIT 1) AS thumbnail_key
            """;

    /** 전달한 ID 순서를 보존하고, 삭제 글과 비활성 게시판 글은 반환하지 않는다. */
    public List<PostSummaryResponse> findAll(List<Long> postIds, Long viewer) {
        if (postIds.isEmpty()) return List.of();
        var params = new MapSqlParameterSource().addValue("ids", postIds).addValue("viewer", viewer);
        var rows = new NamedParameterJdbcTemplate(jdbc).query(SELECT + """
                FROM posts p JOIN boards b ON b.id=p.board_id JOIN users u ON u.id=p.author_id
                LEFT JOIN board_tags t ON t.id=p.tag_id AND t.board_id=p.board_id
                WHERE p.id IN (:ids) AND p.deleted_at IS NULL AND b.is_active=TRUE
                """, params, (rs, index) -> map(rs, viewer, true));
        var byId = rows.stream().collect(Collectors.toMap(row -> Long.parseLong(row.postId()), row -> row));
        return postIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    PostSummaryResponse map(ResultSet rs, Long viewer, boolean includeThumbnail) throws SQLException {
        boolean withdrawn = rs.getObject("author_deleted") != null || "WITHDRAWN".equals(rs.getString("author_status"));
        var author = new Author(rs.getString("author_id"), withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"),
                withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
        String tagId = rs.getString("tag_id");
        List<Tag> tags = tagId == null ? List.of() : List.of(new Tag(tagId, rs.getString("tag_name")));
        String key = rs.getString("thumbnail_key");
        String content = rs.getString("content");
        String preview = content.substring(0, content.offsetByCodePoints(0, Math.min(100, content.codePointCount(0, content.length()))));
        return new PostSummaryResponse(rs.getString("id"), rs.getString("board_id"), rs.getString("title"), preview,
                author, tags, key == null || !includeThumbnail ? null : mediaUrl.resolve(key), rs.getLong("like_count"), rs.getLong("comment_count"),
                rs.getLong("bookmark_count"), rs.getLong("view_count"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                viewer == null ? null : rs.getLong("liked") > 0, viewer == null ? null : rs.getLong("bookmarked") > 0);
    }
}
