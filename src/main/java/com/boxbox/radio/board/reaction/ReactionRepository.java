package com.boxbox.radio.board.reaction;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ReactionRepository {
    private final JdbcTemplate jdbc;

    Optional<Viewer> lockViewer(String loginId) {
        return jdbc.query("SELECT id,status FROM users WHERE login_id=? AND deleted_at IS NULL FOR UPDATE",
                (rs, row) -> new Viewer(rs.getLong("id"), rs.getString("status")), loginId).stream().findFirst();
    }

    boolean lockPost(long postId) {
        return !jdbc.query("""
                SELECT p.id FROM posts p JOIN boards b ON b.id=p.board_id
                WHERE p.id=? AND p.deleted_at IS NULL AND b.is_active=TRUE FOR UPDATE
                """, (rs, row) -> rs.getLong(1), postId).isEmpty();
    }

    Optional<Long> postId(long commentId) {
        return jdbc.query("SELECT post_id FROM comments WHERE id=?", (rs, row) -> rs.getLong(1), commentId)
                .stream().findFirst();
    }

    boolean lockComment(long commentId, long postId) {
        return !jdbc.query("""
                SELECT id FROM comments WHERE id=? AND post_id=? AND deleted_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getLong(1), commentId, postId).isEmpty();
    }

    void setPostReaction(long postId, long userId, String type, boolean registered) {
        // 호출자는 게시글 행을 잠근다. 중복 요청은 최초 등록 시각까지 보존한다.
        if (!registered) {
            jdbc.update("DELETE FROM post_reactions WHERE post_id=? AND user_id=? AND type=?", postId, userId, type);
        } else if (count("SELECT COUNT(*) FROM post_reactions WHERE post_id=? AND user_id=? AND type=?", postId, userId, type) == 0) {
            jdbc.update("INSERT INTO post_reactions(post_id,user_id,type,created_at) VALUES(?,?,?,?)",
                    postId, userId, type, utcNow());
        }
    }

    void setCommentLike(long commentId, long userId, boolean registered) {
        if (!registered) {
            jdbc.update("DELETE FROM comment_likes WHERE comment_id=? AND user_id=?", commentId, userId);
        } else if (count("SELECT COUNT(*) FROM comment_likes WHERE comment_id=? AND user_id=?", commentId, userId) == 0) {
            jdbc.update("INSERT INTO comment_likes(comment_id,user_id,created_at) VALUES(?,?,?)", commentId, userId, utcNow());
        }
    }

    long postCount(long postId, String type) {
        return count("SELECT COUNT(*) FROM post_reactions WHERE post_id=? AND type=?", postId, type);
    }

    long commentCount(long commentId) {
        return count("SELECT COUNT(*) FROM comment_likes WHERE comment_id=?", commentId);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static LocalDateTime utcNow() { return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC); }
    record Viewer(long id, String status) { }
}
