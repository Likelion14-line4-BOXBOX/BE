package com.boxbox.radio.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-post-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql",
        "boxbox.posts.media-base-url=https://images.example.test/media"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/db/post-test-data.sql")
class PostApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PostService service;

    @Test
    void createsPostAndConnectsOnlyOwnUploadsInRequestOrder() throws Exception {
        var result = mvc.perform(post("/api/boards/10/posts").principal(() -> "viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  새 글  \",\"content\":\" 본문 \",\"tagIds\":[\"100\"],\"mediaIds\":[\"17\",\"12\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.boardId").value("10"))
                .andExpect(jsonPath("$.data.postId").isString())
                .andExpect(jsonPath("$.data.createdAt").isString()).andReturn();
        String json = result.getResponse().getContentAsString();
        String id = json.substring(json.indexOf("\"postId\":\"") + 10).split("\"")[0];
        assertThat(jdbc.queryForObject("SELECT title FROM posts WHERE id=?", String.class, Long.valueOf(id))).isEqualTo("새 글");
        assertThat(jdbc.queryForList("SELECT id FROM media_files WHERE post_id=? ORDER BY position", Long.class, Long.valueOf(id)))
                .containsExactly(17L, 12L);
        mvc.perform(get("/api/posts/" + id).principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(" 본문 "))
                .andExpect(jsonPath("$.data.canEdit").value(true));
    }

    @Test
    void detailUsesStoredCountsAndAnonymousStatesAndUtcTimes() throws Exception {
        mvc.perform(get("/api/posts/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.author.nickname").value("작성자"))
                .andExpect(jsonPath("$.data.tags[0].tagId").value("100"))
                .andExpect(jsonPath("$.data.likeCount").value(2)).andExpect(jsonPath("$.data.commentCount").value(2))
                .andExpect(jsonPath("$.data.bookmarkCount").value(1)).andExpect(jsonPath("$.data.viewCount").value(8))
                .andExpect(jsonPath("$.data.likedByMe").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.bookmarkedByMe").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.canEdit").value(false)).andExpect(jsonPath("$.data.canDelete").value(false))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-01T03:04:05Z"))
                .andExpect(jsonPath("$.data.media[0].url").value("https://images.example.test/media/posts/%EA%B0%80%20%EB%82%98.png"))
                .andExpect(jsonPath("$.data.thumbnailUrl").value("https://images.example.test/media/posts/%EA%B0%80%20%EB%82%98.png"));
    }

    @Test
    void memberStatesComeFromRelationsAndOwnership() throws Exception {
        mvc.perform(get("/api/posts/1").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likedByMe").value(true))
                .andExpect(jsonPath("$.data.bookmarkedByMe").value(false)).andExpect(jsonPath("$.data.canEdit").value(true));
        mvc.perform(get("/api/posts/1").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bookmarkedByMe").value(true))
                .andExpect(jsonPath("$.data.canDelete").value(false));
    }

    @Test
    void textOnlyCreationDefaultsToNoTagOrImages() {
        var created = service.create(20, "viewer", Map.of("title", "제목", "content", "본문"));
        var detail = service.detail(Long.parseLong(created.postId()), "viewer", null);
        assertThat(detail.tags()).isEmpty();
        assertThat(detail.media()).isEmpty();
        assertThat(detail.thumbnailUrl()).isNull();
        assertThat(detail.likeCount()).isZero();
    }

    @Test
    void patchPreservesOmittedFieldsAndCanReorderAndClearAttachments() throws Exception {
        mvc.perform(patch("/api/posts/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"수정 글\",\"mediaIds\":[\"11\",\"10\",\"12\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.postId").value("1"))
                .andExpect(jsonPath("$.data.updatedAt").isString());
        assertThat(jdbc.queryForList("SELECT id FROM media_files WHERE post_id=1 ORDER BY position", Long.class))
                .containsExactly(11L, 10L, 12L);
        assertThat(jdbc.queryForObject("SELECT content FROM posts WHERE id=1", String.class)).isEqualTo("기존 본문");
        assertThat(jdbc.queryForObject("SELECT tag_id FROM posts WHERE id=1", Long.class)).isEqualTo(100L);
        mvc.perform(patch("/api/posts/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[],\"mediaIds\":[]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT tag_id FROM posts WHERE id=1", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE post_id=1", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=10", String.class)).isEqualTo("UPLOADED");
    }

    @Test
    void emptyPatchKeepsTimestampAndInactiveExistingTagCanBePreserved() throws Exception {
        jdbc.update("UPDATE posts SET tag_id=101 WHERE id=1");
        mvc.perform(patch("/api/posts/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedAt").value("2026-11-01T03:04:05Z"));
        service.update(1, "viewer", Map.of("title", "새 제목"));
        assertThat(jdbc.queryForObject("SELECT tag_id FROM posts WHERE id=1", Long.class)).isEqualTo(101L);
    }

    @Test
    void deleteHidesPostAndDecreasesBoardCountWithoutRemovingOtherData() throws Exception {
        mvc.perform(delete("/api/posts/1").principal(() -> "viewer"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/posts/1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/boards").param("teamId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].postCount").value(1));
        assertThat(jdbc.queryForObject("SELECT content FROM posts WHERE id=1", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=10", String.class)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_reactions WHERE post_id=1", Long.class)).isEqualTo(3);
        mvc.perform(delete("/api/posts/1").principal(() -> "viewer")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\"제목\"}", "{\"title\":null,\"content\":\"본문\"}",
            "{\"title\":\"   \",\"content\":\"본문\"}", "{\"title\":\"123456789012345678901\",\"content\":\"본문\"}",
            "{\"title\":1,\"content\":\"본문\"}", "{\"title\":\"제목\",\"content\":\" \\n \",\"tagIds\":[]}",
            "{\"title\":\"제목\",\"content\":null}", "{\"title\":\"제목\",\"content\":\"본문\",\"boardId\":\"20\"}"})
    void invalidCreationDoesNotInsertAnything(String body) throws Exception {
        mvc.perform(post("/api/boards/10/posts").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"tagIds\":[\"200\"]}", "{\"tagIds\":[\"101\"]}", "{\"tagIds\":[\"999\"]}",
            "{\"tagIds\":[\"100\",\"100\"]}", "{\"tagIds\":null}", "{\"tagIds\":[100]}",
            "{\"mediaIds\":[\"13\"]}", "{\"mediaIds\":[\"14\"]}", "{\"mediaIds\":[\"15\"]}",
            "{\"mediaIds\":[\"16\"]}", "{\"mediaIds\":[\"999\"]}", "{\"mediaIds\":null}",
            "{\"mediaIds\":[12]}", "{\"mediaIds\":[\"12\",\"012\"]}",
            "{\"mediaIds\":[\"10\",\"11\",\"12\",\"17\",\"18\",\"19\"]}",
            "{\"title\":null}", "{\"content\":\"   \"}", "{\"authorId\":\"2\"}"})
    void invalidPatchPreservesPostAndAllAttachments(String body) throws Exception {
        mvc.perform(patch("/api/posts/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT title FROM posts WHERE id=1", String.class)).isEqualTo("기존 글");
        assertThat(jdbc.queryForList("SELECT id FROM media_files WHERE post_id=1 ORDER BY position", Long.class)).containsExactly(10L,11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{broken", ""})
    void malformedJsonHasCommon400Response(String body) throws Exception {
        mvc.perform(patch("/api/posts/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void authenticationCannotBeReplacedByUnverifiedHeaders() throws Exception {
        mvc.perform(post("/api/boards/10/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"본문\"}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/posts/1").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/posts/1").header("Authorization", "Bearer viewer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/1").header("Authorization", "Bearer viewer")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_view_dedup", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "withdrawn", "deleted"})
    void unavailableAccountsCannotWrite(String login) throws Exception {
        mvc.perform(patch("/api/posts/1").principal(() -> login).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restrictedAndOtherMembersCannotChangePost() throws Exception {
        mvc.perform(post("/api/boards/10/posts").principal(() -> "suspended").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("WRITING_RESTRICTED"));
        mvc.perform(patch("/api/posts/1").principal(() -> "other").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_POST_AUTHOR"));
        mvc.perform(delete("/api/posts/1").principal(() -> "other")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4", "999"})
    void deletedInactiveAndMissingPostsReturn404(String id) throws Exception {
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        mvc.perform(patch("/api/posts/" + id).principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/posts/" + id).principal(() -> "viewer")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void malformedPostIdsReturn400(String id) throws Exception {
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isBadRequest());
    }

    @Test
    void missingOrInactiveBoardsCannotAcceptPosts() throws Exception {
        for (String board : new String[]{"50", "999"}) {
            mvc.perform(post("/api/boards/" + board + "/posts").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
        }
    }

    @Test
    void viewsDeduplicateSessionsAndMembersAndExpireFromLastCountedTime() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/api/posts/1").session(session)).andExpect(jsonPath("$.data.viewCount").value(8));
        mvc.perform(get("/api/posts/1").session(session)).andExpect(jsonPath("$.data.viewCount").value(8));
        mvc.perform(get("/api/posts/1").session(new MockHttpSession())).andExpect(jsonPath("$.data.viewCount").value(9));
        mvc.perform(get("/api/posts/1").principal(() -> "viewer")).andExpect(jsonPath("$.data.viewCount").value(10));
        mvc.perform(get("/api/posts/1").principal(() -> "viewer").session(new MockHttpSession()))
                .andExpect(jsonPath("$.data.viewCount").value(10));
        jdbc.update("UPDATE post_view_dedup SET last_counted_at='2020-01-01 00:00:00'");
        mvc.perform(get("/api/posts/1").session(session)).andExpect(jsonPath("$.data.viewCount").value(11));
        var unchangedAt = jdbc.queryForObject("SELECT updated_at FROM posts WHERE id=1",
                (rs, row) -> rs.getObject(1, java.time.LocalDateTime.class));
        assertThat(unchangedAt).isEqualTo(java.time.LocalDateTime.of(2026, 11, 1, 3, 4, 5));
    }

    @Test
    void concurrentViewsOfSameMemberCountOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.detail(1, "viewer", null).viewCount(); });
            var second = pool.submit(() -> { start.await(); return service.detail(1, "viewer", null).viewCount(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(8);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(8);
        }
        assertThat(jdbc.queryForObject("SELECT view_count FROM posts WHERE id=1", Long.class)).isEqualTo(8);
    }

    @Test
    void concurrentPartialUpdatesDoNotLoseOtherFields() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.update(1, "viewer", Map.of("title", "동시 제목")); });
            var second = pool.submit(() -> { start.await(); return service.update(1, "viewer", Map.of("content", "동시 본문")); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT title FROM posts WHERE id=1", String.class)).isEqualTo("동시 제목");
        assertThat(jdbc.queryForObject("SELECT content FROM posts WHERE id=1", String.class)).isEqualTo("동시 본문");
    }

    @Test
    void maximumUnicodeTitleAndFiveImagesAreAccepted() {
        var created = service.create(10, "viewer", Map.of("title", "😀".repeat(20), "content", "가".repeat(10001),
                "mediaIds", java.util.List.of("12", "17", "18", "19", "20")));
        var detail = service.detail(Long.parseLong(created.postId()), "viewer", null);
        assertThat(detail.title()).isEqualTo("😀".repeat(20));
        assertThat(detail.content()).hasSize(10001);
        assertThat(detail.preview()).hasSize(100);
        assertThat(detail.media()).hasSize(5);
    }

    @Test
    void timestampsAreStoredAsUtcAndRoundTripWithoutDependingOnJvmTimezone() {
        var created = service.create(20, "viewer", Map.of("title", "UTC 검증", "content", "본문"));
        long id = Long.parseLong(created.postId());
        var stored = jdbc.queryForObject("SELECT created_at FROM posts WHERE id=?",
                (rs, row) -> rs.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC), id);
        assertThat(stored).isEqualTo(created.createdAt());
        assertThat(service.detail(id, "viewer", null).createdAt()).isEqualTo(created.createdAt());
        var updated = service.update(id, "viewer", Map.of("title", "UTC 수정"));
        assertThat(service.detail(id, "viewer", null).updatedAt()).isEqualTo(updated.updatedAt());
        var counted = jdbc.queryForObject("SELECT last_counted_at FROM post_view_dedup WHERE post_id=?",
                (rs, row) -> rs.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC), id);
        assertThat(counted).isBetween(java.time.Instant.now().minusSeconds(10), java.time.Instant.now());
    }

    @Test
    void unavailableMediaUrlRollsBackViewCount() throws Exception {
        jdbc.update("UPDATE media_files SET object_key='../invalid' WHERE id=10");
        mvc.perform(get("/api/posts/1")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MEDIA_SERVICE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT view_count FROM posts WHERE id=1", Long.class)).isEqualTo(7);
    }
}
