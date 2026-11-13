package com.boxbox.radio.board.comment;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.comment.dto.CommentResponse;
import com.boxbox.radio.board.post.dto.PostDetailResponse.Author;
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
class CommentRepository {
    private final JdbcTemplate jdbc;
    private static final String SUMMARY = """
            SELECT c.*,u.nickname,u.avatar_asset_id,u.status AS author_status,u.deleted_at AS author_deleted,
                (SELECT COUNT(*) FROM comment_likes l WHERE l.comment_id=c.id) AS like_count,
                (SELECT COUNT(*) FROM comment_likes l WHERE l.comment_id=c.id AND l.user_id=:viewer) AS liked,
                CASE WHEN c.parent_id IS NULL THEN
                    (SELECT COUNT(*) FROM comments r WHERE r.post_id=c.post_id AND r.parent_id=c.id AND r.deleted_at IS NULL)
                    ELSE 0 END AS reply_count
            FROM comments c JOIN users u ON u.id=c.author_id
            """;

    Optional<Viewer> viewer(String loginId, boolean lock) {
        return jdbc.query("SELECT id,status FROM users WHERE login_id=? AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Viewer(rs.getLong("id"), rs.getString("status")), loginId).stream().findFirst();
    }

    boolean readablePost(long postId, boolean lock) {
        return !jdbc.query("""
                SELECT p.id FROM posts p JOIN boards b ON b.id=p.board_id
                WHERE p.id=? AND p.deleted_at IS NULL AND b.is_active=TRUE
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), postId).isEmpty();
    }

    long postId(long commentId) {
        return jdbc.query("SELECT post_id FROM comments WHERE id=?", (rs, row) -> rs.getLong(1), commentId)
                .stream().findFirst().orElseThrow(CommentRepository::notFound);
    }

    StoredComment lockComment(long commentId, long postId) {
        return jdbc.query("SELECT id,post_id,author_id,parent_id,deleted_at FROM comments WHERE id=? AND post_id=? FOR UPDATE",
                (rs, row) -> new StoredComment(rs.getLong("id"), rs.getLong("post_id"), rs.getLong("author_id"),
                        rs.getObject("parent_id", Long.class), instant(rs,"deleted_at")), commentId, postId)
                .stream().findFirst().orElseThrow(CommentRepository::notFound);
    }

    long maxId(long postId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM comments WHERE post_id=?", Long.class, postId);
    }

    List<CommentResponse> page(long postId, int size, long maxId, Instant anchor, CommentCursor cursor, Long viewer) {
        String after = cursor == null ? "" : " AND (c.created_at>:lastTime OR (c.created_at=:lastTime AND c.id>:lastId)) ";
        var params = new MapSqlParameterSource().addValue("post",postId).addValue("maxId",maxId)
                .addValue("anchor",utc(anchor)).addValue("viewer",viewer).addValue("limit",size+1);
        if (cursor != null) params.addValue("lastTime",utc(cursor.createdAt())).addValue("lastId",cursor.id());
        return named().query(SUMMARY + " WHERE c.post_id=:post AND c.id<=:maxId AND c.created_at<=:anchor "
                + after + " ORDER BY c.created_at,c.id LIMIT :limit", params, (rs, row) -> response(rs, viewer));
    }

    CommentResponse summary(long commentId, Long viewer) {
        return named().query(SUMMARY + " WHERE c.id=:id",
                new MapSqlParameterSource().addValue("id",commentId).addValue("viewer",viewer),
                (rs, row) -> response(rs, viewer)).stream().findFirst().orElseThrow(CommentRepository::notFound);
    }

    long insert(long postId, long authorId, CommentInput input, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO comments(post_id,author_id,parent_id,content,created_at,updated_at) VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,postId);
            statement.setLong(2,authorId);
            statement.setObject(3,input.parentId());
            statement.setString(4,input.content());
            statement.setObject(5,utc(now));
            statement.setObject(6,utc(now));
            return statement;
        },keys);
        return keys.getKey().longValue();
    }

    void update(long commentId, String content, Instant now) {
        jdbc.update("UPDATE comments SET content=?,updated_at=? WHERE id=?",content,utc(now),commentId);
    }

    void delete(long commentId, Instant now) {
        jdbc.update("UPDATE comments SET content=NULL,deleted_at=?,updated_at=? WHERE id=?",utc(now),utc(now),commentId);
    }

    private static CommentResponse response(ResultSet rs, Long viewer) throws SQLException {
        boolean deleted = rs.getObject("deleted_at") != null;
        boolean withdrawn = rs.getObject("author_deleted") != null || "WITHDRAWN".equals(rs.getString("author_status"));
        Author author = deleted ? null : new Author(rs.getString("author_id"),
                withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"), withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
        return new CommentResponse(rs.getString("id"),rs.getString("post_id"),rs.getString("parent_id"),author,
                deleted ? null : rs.getString("content"),deleted,deleted ? 0 : rs.getLong("like_count"),
                viewer == null ? null : !deleted && rs.getLong("liked") > 0,rs.getLong("reply_count"),
                instant(rs,"created_at"),instant(rs,"updated_at"));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND,"COMMENT_NOT_FOUND","댓글을 찾을 수 없습니다.");
    }

    static ApiException postNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND,"POST_NOT_FOUND","게시글을 찾을 수 없습니다.");
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        LocalDateTime value = rs.getObject(column,LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(jdbc); }
    record Viewer(long id,String status) { }
    record StoredComment(long id,long postId,long authorId,Long parentId,Instant deletedAt) { }
}
