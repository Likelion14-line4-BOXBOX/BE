package com.boxbox.radio.reaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.comment.CommentService;
import com.boxbox.radio.post.PostService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-reaction-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/comment-schema.sql",
        "boxbox.posts.media-base-url=https://images.example.test/media"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/comment-cleanup.sql", "/db/post-test-data.sql", "/db/comment-test-data.sql"})
class ReactionApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ReactionService service;
    @Autowired protected PostService posts;
    @Autowired protected CommentService comments;

    static Stream<Arguments> endpoints() {
        return Stream.of("PUT", "DELETE").flatMap(method -> Stream.of(
                Arguments.of(method, "/api/posts/2/like"),
                Arguments.of(method, "/api/posts/2/bookmark"),
                Arguments.of(method, "/api/comments/6/like")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "BOOKMARK", "COMMENT"})
    void registerRetryAndCancelHaveIdempotentContracts(String kind) throws Exception {
        String path = path(kind);
        String countField = kind.equals("BOOKMARK") ? "bookmarkCount" : "likeCount";
        String stateField = kind.equals("BOOKMARK") ? "bookmarkedByMe" : "likedByMe";
        String idField = kind.equals("COMMENT") ? "commentId" : "postId";
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put(path).principal(() -> "viewer"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data." + idField).value(kind.equals("COMMENT") ? "6" : "2"))
                    .andExpect(jsonPath("$.data." + stateField).value(true)).andExpect(jsonPath("$.data." + countField).value(1))
                    .andExpect(jsonPath("$.data.length()").value(3)).andExpect(jsonPath("$.meta.requestId").isString());
            LocalDateTime created = createdAt(kind);
            assertThat(created).isBetween(before, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
            if (attempt == 0) before = created;
            else assertThat(created).isEqualTo(before);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(path).principal(() -> "viewer"))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertThat(count(kind)).isZero();
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyMutationRequiresAuthentication(String method, String path) throws Exception {
        var before = snapshot();
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void suspendedAccountsCannotRegisterOrCancel(String method, String path) throws Exception {
        var before = snapshot();
        mvc.perform(request(HttpMethod.valueOf(method), path).principal(() -> "suspended"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REACTION_RESTRICTED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "withdrawn", "deleted", " "})
    void invalidPrincipalsCannotMutateAnyRelation(String loginId) throws Exception {
        var before = snapshot();
        for (var endpoint : endpoints().toList()) {
            Object[] args = endpoint.get();
            mvc.perform(request(HttpMethod.valueOf((String) args[0]), (String) args[1]).principal(() -> loginId))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void forgedHeadersAndUnverifiedBearerNeverIdentifyAMember() throws Exception {
        mvc.perform(put("/api/posts/2/like").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/comments/6/like").header("Authorization", "Bearer viewer")).andExpect(status().isUnauthorized());
        assertThat(count("LIKE")).isZero();
        assertThat(count("COMMENT")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidIdsReturn400(String id) throws Exception {
        for (String template : List.of("/api/posts/%s/like", "/api/posts/%s/bookmark", "/api/comments/%s/like")) {
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
                mvc.perform(request(method, template.formatted(id)).principal(() -> "viewer"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4", "999"})
    void deletedInactiveAndMissingPostsCannotBeReactedTo(String id) throws Exception {
        var before = snapshot();
        for (String suffix : List.of("like", "bookmark")) {
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
                mvc.perform(request(method, "/api/posts/" + id + "/" + suffix).principal(() -> "viewer"))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
            }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "5", "9", "10", "999"})
    void deletedOrInaccessibleCommentsCannotBeReactedTo(String id) throws Exception {
        var before = snapshot();
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/comments/" + id + "/like").principal(() -> "viewer"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ownAndOtherReactionsAndReactionTypesRemainIndependent() throws Exception {
        service.bookmarkPost(1, "viewer");
        mvc.perform(delete("/api/posts/1/like").principal(() -> "viewer")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/comments/1/like").principal(() -> "viewer")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_reactions WHERE post_id=1 AND user_id=2", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_reactions WHERE post_id=1 AND type='BOOKMARK'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id=1 AND user_id=2", Long.class)).isEqualTo(1);
        assertThat(service.likePost(1, "viewer").likeCount()).isEqualTo(2);
        assertThat(service.likeComment(1, "viewer").likeCount()).isEqualTo(2);
    }

    @Test
    void detailListsSearchAndCommentsReflectMutationsAndAnonymousStates() throws Exception {
        service.unlikePost(1, "viewer");
        service.bookmarkPost(1, "viewer");
        service.unlikeComment(4, "viewer");
        service.likeComment(4, "other");
        for (String path : List.of("/api/boards/10/posts", "/api/posts?boardId=10")) {
            mvc.perform(get(path).principal(() -> "viewer"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].postId").value("1"))
                    .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                    .andExpect(jsonPath("$.data.items[0].likedByMe").value(false))
                    .andExpect(jsonPath("$.data.items[0].bookmarkCount").value(2))
                    .andExpect(jsonPath("$.data.items[0].bookmarkedByMe").value(true));
        }
        mvc.perform(get("/api/posts/1").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.likedByMe").value(false)).andExpect(jsonPath("$.data.bookmarkedByMe").value(true));
        mvc.perform(get("/api/posts/1/comments").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[3].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[3].likedByMe").value(false));
        mvc.perform(get("/api/posts/1/comments").principal(() -> "other"))
                .andExpect(jsonPath("$.data.items[3].likedByMe").value(true));
        mvc.perform(get("/api/posts/1")).andExpect(jsonPath("$.data.likedByMe").value(nullValue()))
                .andExpect(jsonPath("$.data.bookmarkedByMe").value(nullValue()));
        mvc.perform(get("/api/posts/1/comments")).andExpect(jsonPath("$.data.items[3].likedByMe").value(nullValue()));
        service.unbookmarkPost(1, "viewer");
        mvc.perform(get("/api/posts/1").principal(() -> "viewer"))
                .andExpect(jsonPath("$.data.bookmarkCount").value(1)).andExpect(jsonPath("$.data.bookmarkedByMe").value(false));
    }

    @Test
    void archivedPostsAndSurvivingRepliesToDeletedParentsRemainReactable() {
        jdbc.update("UPDATE posts SET tag_id=101 WHERE id=2");
        assertThat(service.likePost(2, "viewer").likeCount()).isEqualTo(1);
        assertThat(service.bookmarkPost(2, "other").bookmarkCount()).isEqualTo(1);
        comments.delete(1, "viewer");
        assertThat(service.likeComment(4, "other").likeCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.likeComment(1, "other")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("COMMENT_NOT_FOUND"));
    }

    @Test
    void reactionsDoNotChangeContentTimestampsViewsOrDedup() {
        var before = contentSnapshot();
        service.likePost(2, "viewer");
        service.bookmarkPost(2, "viewer");
        service.likeComment(6, "viewer");
        service.unlikePost(2, "viewer");
        service.unbookmarkPost(2, "viewer");
        service.unlikeComment(6, "viewer");
        assertThat(contentSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "BOOKMARK", "COMMENT"})
    void concurrentRetriesOnlyCreateOneRelationAndRepeatedDeletesSucceed(String kind) throws Exception {
        var tasks = new ArrayList<Callable<Long>>();
        for (int i = 0; i < 8; i++) tasks.add(() -> register(kind, "viewer"));
        assertThat(concurrently(tasks)).containsOnly(1L);
        assertThat(count(kind)).isEqualTo(1);
        var deletes = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 8; i++) deletes.add(() -> { cancel(kind, "viewer"); return true; });
        assertThat(concurrently(deletes)).containsOnly(true);
        assertThat(count(kind)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "BOOKMARK", "COMMENT"})
    void concurrentDifferentMembersReturnCountsInCommitOrder(String kind) throws Exception {
        var tasks = new ArrayList<Callable<Long>>();
        for (int i = 10; i < 18; i++) {
            String login = "member" + i;
            jdbc.update("INSERT INTO users(id,login_id,password_hash,nickname,status) VALUES(?,?,'test-only-hash',?,'ACTIVE')", i, login, login);
            tasks.add(() -> register(kind, login));
        }
        assertThat(concurrently(tasks)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(count(kind)).isEqualTo(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "BOOKMARK", "COMMENT"})
    void concurrentRegisterAndCancelRemainConsistent(String kind) throws Exception {
        var tasks = List.<Callable<Boolean>>of(() -> { register(kind, "viewer"); return true; },
                () -> { cancel(kind, "viewer"); return true; });
        assertThat(concurrently(tasks)).containsOnly(true);
        assertThat(count(kind)).isIn(0L, 1L);
        assertThat(register(kind, "viewer")).isEqualTo(1);
        cancel(kind, "viewer");
        assertThat(count(kind)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "BOOKMARK", "COMMENT"})
    void targetDeletionAndReactionSerializeWithoutResurrection(String kind) throws Exception {
        var results = concurrently(List.<Callable<String>>of(() -> {
            try { register(kind, "other"); return "REGISTERED"; }
            catch (ApiException error) { return error.getCode(); }
        }, () -> {
            if (kind.equals("COMMENT")) comments.delete(6, "viewer");
            else posts.delete(2, "other");
            return "DELETED";
        }));
        String missing = kind.equals("COMMENT") ? "COMMENT_NOT_FOUND" : "POST_NOT_FOUND";
        assertThat(results.getFirst()).isIn("REGISTERED", missing);
        assertThat(results.get(1)).isEqualTo("DELETED");
        assertThat(count(kind)).isEqualTo(results.getFirst().equals("REGISTERED") ? 1 : 0);
        assertThatThrownBy(() -> register(kind, "viewer")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo(missing));
        assertThatThrownBy(() -> cancel(kind, "other")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo(missing));
    }

    protected long register(String kind, String login) {
        return switch (kind) {
            case "LIKE" -> service.likePost(2, login).likeCount();
            case "BOOKMARK" -> service.bookmarkPost(2, login).bookmarkCount();
            case "COMMENT" -> service.likeComment(6, login).likeCount();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private void cancel(String kind, String login) {
        switch (kind) {
            case "LIKE" -> service.unlikePost(2, login);
            case "BOOKMARK" -> service.unbookmarkPost(2, login);
            case "COMMENT" -> service.unlikeComment(6, login);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private String path(String kind) {
        return kind.equals("COMMENT") ? "/api/comments/6/like" : "/api/posts/2/" + (kind.equals("LIKE") ? "like" : "bookmark");
    }

    protected long count(String kind) {
        return kind.equals("COMMENT") ? jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id=6", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM post_reactions WHERE post_id=2 AND type=?", Long.class, kind);
    }

    private LocalDateTime createdAt(String kind) {
        return kind.equals("COMMENT") ? jdbc.queryForObject("SELECT created_at FROM comment_likes WHERE comment_id=6", LocalDateTime.class)
                : jdbc.queryForObject("SELECT created_at FROM post_reactions WHERE post_id=2 AND type=?", LocalDateTime.class, kind);
    }

    private List<Object> snapshot() {
        return List.of(contentSnapshot(), jdbc.queryForList("SELECT * FROM post_reactions ORDER BY post_id,user_id,type"),
                jdbc.queryForList("SELECT * FROM comment_likes ORDER BY comment_id,user_id"));
    }

    private List<Object> contentSnapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM posts ORDER BY id"), jdbc.queryForList("SELECT * FROM comments ORDER BY id"),
                jdbc.queryForList("SELECT * FROM users ORDER BY id"), jdbc.queryForList("SELECT * FROM post_view_dedup ORDER BY post_id,visitor_hash"));
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = tasks.stream().map(task -> pool.submit(() -> { start.await(); return task.call(); })).toList();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        }
    }
}
