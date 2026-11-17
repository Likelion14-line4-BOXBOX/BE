package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import com.boxbox.radio.post.dto.PostDetailResponse.Tag;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PostRepository {
    private final JdbcTemplate jdbc;

    Optional<Viewer> viewer(String loginId, boolean lock) {
        return jdbc.query("SELECT id, status FROM users WHERE login_id=? AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Viewer(rs.getLong("id"), rs.getString("status")), loginId).stream().findFirst();
    }

    boolean activeBoard(long boardId) {
        return count("SELECT COUNT(*) FROM boards WHERE id=? AND is_active=TRUE", boardId) == 1;
    }

    void validateTag(long boardId, List<Long> tags) {
        if (!tags.isEmpty() && count("SELECT COUNT(*) FROM board_tags WHERE id=? AND board_id=? AND is_active=TRUE",
                tags.getFirst(), boardId) != 1) {
            throw ApiException.invalidRequest("해당 게시판의 활성 태그를 선택해 주세요.");
        }
    }

    StoredPost lockPost(long postId) {
        return jdbc.query("""
                SELECT p.* FROM posts p JOIN boards b ON b.id=p.board_id
                WHERE p.id=? AND p.deleted_at IS NULL AND b.is_active=TRUE FOR UPDATE
                """, (rs, row) -> new StoredPost(rs.getLong("id"), rs.getLong("board_id"),
                rs.getLong("author_id"), rs.getObject("tag_id", Long.class), rs.getString("title"),
                rs.getString("content"), rs.getLong("view_count"), instant(rs, "created_at"),
                instant(rs, "updated_at")), postId).stream().findFirst().orElseThrow(PostRepository::notFound);
    }

    long insert(long boardId, long authorId, PostInput input, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO posts(board_id,author_id,tag_id,title,content,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, boardId);
            statement.setLong(2, authorId);
            statement.setObject(3, input.tagIds().isEmpty() ? null : input.tagIds().getFirst());
            statement.setString(4, input.title());
            statement.setString(5, input.content());
            statement.setObject(6, utc(now));
            statement.setObject(7, utc(now));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    void update(StoredPost post, PostInput input, Instant now) {
        Long tag = input.has("tagIds") ? (input.tagIds().isEmpty() ? null : input.tagIds().getFirst()) : post.tagId();
        jdbc.update("UPDATE posts SET title=?,content=?,tag_id=?,updated_at=? WHERE id=?",
                input.has("title") ? input.title() : post.title(),
                input.has("content") ? input.content() : post.content(), tag, utc(now), post.id());
    }

    List<StoredMedia> media(long postId) {
        return jdbc.query("SELECT * FROM media_files WHERE post_id=? AND status='ATTACHED' AND deleted_at IS NULL ORDER BY position FOR UPDATE",
                PostRepository::readMedia, postId);
    }

    List<StoredMedia> lockMedia(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return new NamedParameterJdbcTemplate(jdbc).query(
                "SELECT * FROM media_files WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource("ids", ids), PostRepository::readMedia);
    }

    void detach(long postId, Instant now) {
        jdbc.update("""
                UPDATE media_files SET post_id=NULL,position=NULL,status='UPLOADED',updated_at=?
                WHERE post_id=? AND status='ATTACHED' AND deleted_at IS NULL
                """, utc(now), postId);
    }

    void attach(long postId, List<Long> ids, Instant now) {
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("""
                    UPDATE media_files SET post_id=?,position=?,status='ATTACHED',expires_at=NULL,updated_at=? WHERE id=?
                    """, postId, i + 1, utc(now), ids.get(i));
        }
    }

    void delete(long postId, Instant now) {
        jdbc.update("UPDATE posts SET deleted_at=?,updated_at=?,content=NULL WHERE id=?",
                utc(now), utc(now), postId);
        jdbc.update("""
                UPDATE media_files SET status='DELETED',deleted_at=?,updated_at=?,post_id=NULL,position=NULL WHERE post_id=?
                """, utc(now), utc(now), postId);
    }

    long countView(StoredPost post, byte[] visitor, Instant now, Instant cutoff) {
        var previous = jdbc.query("SELECT last_counted_at FROM post_view_dedup WHERE post_id=? AND visitor_hash=? FOR UPDATE",
                (rs, row) -> instant(rs, "last_counted_at"), post.id(), visitor);
        if (!previous.isEmpty() && previous.getFirst().isAfter(cutoff)) return post.viewCount();
        if (previous.isEmpty()) {
            jdbc.update("INSERT INTO post_view_dedup(post_id,visitor_hash,last_counted_at) VALUES (?,?,?)",
                    post.id(), visitor, utc(now));
        } else {
            jdbc.update("UPDATE post_view_dedup SET last_counted_at=? WHERE post_id=? AND visitor_hash=?",
                    utc(now), post.id(), visitor);
        }
        jdbc.update("UPDATE posts SET view_count=view_count+1 WHERE id=?", post.id());
        return post.viewCount() + 1;
    }

    Author author(long authorId) {
        return jdbc.query("SELECT id,nickname,avatar_asset_id,deleted_at,status FROM users WHERE id=?", (rs, row) -> {
            boolean withdrawn = rs.getTimestamp("deleted_at") != null || "WITHDRAWN".equals(rs.getString("status"));
            return new Author(Long.toString(authorId), withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"),
                    withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
        }, authorId).getFirst();
    }

    List<Tag> tags(Long tagId) {
        if (tagId == null) return List.of();
        return jdbc.query("SELECT id,name FROM board_tags WHERE id=?",
                (rs, row) -> new Tag(rs.getString("id"), rs.getString("name")), tagId);
    }

    long reactionCount(long postId, String type) {
        return count("SELECT COUNT(*) FROM post_reactions WHERE post_id=? AND type=?", postId, type);
    }

    boolean reacted(long postId, long userId, String type) {
        return count("SELECT COUNT(*) FROM post_reactions WHERE post_id=? AND user_id=? AND type=?", postId, userId, type) > 0;
    }

    long commentCount(long postId) {
        return count("SELECT COUNT(*) FROM comments WHERE post_id=? AND deleted_at IS NULL", postId);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    // DATETIME은 시간대가 없으므로 JDBC/서버 기본 시간대에 기대지 않고 UTC 벽시각을 저장한다.
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "게시글을 찾을 수 없습니다.");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static StoredMedia readMedia(ResultSet rs, int row) throws SQLException {
        return new StoredMedia(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("object_key"),
                rs.getString("purpose"), rs.getString("status"), rs.getObject("post_id", Long.class),
                rs.getInt("width"), rs.getInt("height"), instant(rs, "expires_at"), instant(rs, "deleted_at"));
    }

    record Viewer(long id, String status) { }
    record StoredPost(long id, long boardId, long authorId, Long tagId, String title, String content,
                      long viewCount, Instant createdAt, Instant updatedAt) { }
    record StoredMedia(long id, long ownerId, String objectKey, String purpose, String status, Long postId,
                       int width, int height, Instant expiresAt, Instant deletedAt) { }
}
