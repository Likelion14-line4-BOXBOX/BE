package com.boxbox.garage.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.garage.activity.dto.MyPostsResponse;
import com.boxbox.radio.post.PostService;
import com.boxbox.radio.reaction.ReactionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-my-posts-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql",
        "boxbox.posts.media-base-url=https://images.example.test/media"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/post-test-data.sql", "/db/my-posts-test-data.sql"})
class MyPostsApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MyPostsService service;
    @Autowired protected PostService posts;
    @Autowired protected ReactionService reactions;

    @Test
    void authoredListHasExistingSummaryContractAndKeepsArchivedTags() throws Exception {
        mvc.perform(get("/api/me/posts?filter=AUTHORED").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].postId").value(is(List.of("11", "6", "5", "1"))))
                .andExpect(jsonPath("$.data.length()").value(3)).andExpect(jsonPath("$.data.items[3].length()").value(14))
                .andExpect(jsonPath("$.data.items[3].author.userId").value("1"))
                .andExpect(jsonPath("$.data.items[3].tags[0].tagId").value("100"))
                .andExpect(jsonPath("$.data.items[2].tags[0].name").value("과거 태그"))
                .andExpect(jsonPath("$.data.items[3].thumbnailUrl").value("https://images.example.test/media/posts/%EA%B0%80%20%EB%82%98.png"))
                .andExpect(jsonPath("$.data.items[3].likeCount").value(2))
                .andExpect(jsonPath("$.data.items[3].commentCount").value(2))
                .andExpect(jsonPath("$.data.items[3].bookmarkCount").value(1))
                .andExpect(jsonPath("$.data.items[3].viewCount").value(7))
                .andExpect(jsonPath("$.data.items[3].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[3].bookmarkedByMe").value(false))
                .andExpect(jsonPath("$.data.items[3].createdAt").value("2026-11-01T03:04:05Z"))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue())).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.meta.requestId").isString());
    }

    @ParameterizedTest
    @CsvSource({"AUTHORED,11 6 5 1", "LIKED,10 9 8 7 5 1", "BOOKMARKED,8 5 2"})
    void filtersReturnOnlyOwnActivityInActivityTimeAndIdOrder(String filter, String expected) {
        assertThat(ids(service.list("viewer", Map.of("filter", filter)))).containsExactly(expected.split(" "));
    }

    @ParameterizedTest
    @CsvSource({"AUTHORED,12 8 7 2", "LIKED,6 1", "BOOKMARKED,7 1"})
    void otherMemberHasADifferentScope(String filter, String expected) {
        assertThat(ids(service.list("other", Map.of("filter", filter)))).containsExactly(expected.split(" "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORED", "LIKED", "BOOKMARKED"})
    void emptyMemberReturnsEmptyPage(String filter) throws Exception {
        mvc.perform(get("/api/me/posts").param("filter", filter).principal(() -> "empty"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue())).andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void anonymousSpoofedAndUnverifiedBearerRequestsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/me/posts?filter=AUTHORED")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/posts?filter=LIKED").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/posts?filter=BOOKMARKED").header("Authorization", "Bearer viewer"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "suspended", "withdrawn", "deleted", " "})
    void invalidAccountsCannotUseAnyFilter(String login) throws Exception {
        for (String filter : List.of("AUTHORED", "LIKED", "BOOKMARKED")) {
            mvc.perform(get("/api/me/posts").param("filter", filter).principal(() -> login))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "filter=", "filter=authored", "filter=COMMENTS", "filter=AUTHORED&size=0",
            "filter=AUTHORED&size=101", "filter=AUTHORED&size=-1", "filter=AUTHORED&size=1.5", "filter=AUTHORED&size=",
            "filter=AUTHORED&size=999999999999", "filter=AUTHORED&userId=2", "filter=AUTHORED&sort=LATEST",
            "filter=AUTHORED&cursor=", "filter=AUTHORED&cursor=broken"})
    void invalidParametersReturn400(String query) throws Exception {
        mvc.perform(get("/api/me/posts?" + query).principal(() -> "viewer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORED", "LIKED", "BOOKMARKED"})
    void cursorTraversalHasNoGapsOrDuplicatesAndAcceptsMaximumSize(String filter) {
        List<String> expected = ids(service.list("viewer", Map.of("filter", filter, "size", "100")));
        var params = new HashMap<>(Map.of("filter", filter, "size", "2"));
        var actual = new ArrayList<String>();
        for (int page = 0; page < 10; page++) {
            var result = service.list("viewer", params);
            actual.addAll(ids(result));
            if (!result.hasNext()) { assertThat(result.nextCursor()).isNull(); break; }
            assertThat(result.nextCursor()).isNotBlank();
            params.put("cursor", result.nextCursor());
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void defaultSizeIs20AndCursorIsBoundToMemberFilterAndSize() throws Exception {
        for (int id = 20; id <= 40; id++) insertPost(id, 1, Instant.parse("2026-11-10T00:00:00Z"));
        var first = service.list("viewer", Map.of("filter", "AUTHORED"));
        assertThat(first.items()).hasSize(20);
        assertThat(first.hasNext()).isTrue();
        String cursor = first.nextCursor();
        mvc.perform(get("/api/me/posts").param("filter", "AUTHORED").param("cursor", cursor).principal(() -> "other"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/posts").param("filter", "LIKED").param("cursor", cursor).principal(() -> "viewer"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/posts").param("filter", "AUTHORED").param("size", "1").param("cursor", cursor).principal(() -> "viewer"))
                .andExpect(status().isBadRequest());
        assertThat(service.list("viewer", Map.of("filter", "AUTHORED", "cursor", cursor)).items()).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void malformedCursorFieldsAreRejected(int field) throws Exception {
        String cursor = service.list("viewer", Map.of("filter", "AUTHORED", "size", "1")).nextCursor();
        String[] parts = unpack(cursor);
        parts[field] = switch (field) {
            case 0 -> "post-search-1";
            case 1 -> "2";
            case 2 -> "BOOKMARKED";
            case 3 -> "100";
            case 4 -> Instant.now().plusSeconds(100).toString();
            case 5 -> "0";
            case 6 -> "0999-01-01T00:00:00Z";
            case 7 -> "0";
            default -> throw new IllegalArgumentException();
        };
        mvc.perform(get("/api/me/posts").param("filter", "AUTHORED").param("size", "1").param("cursor", pack(parts))
                .principal(() -> "viewer")).andExpect(status().isBadRequest());
    }

    @Test
    void newPostCannotEnterTraversalEvenWhenItsTimestampIsBackdatedBeforeAnchor() {
        var params = new HashMap<>(Map.of("filter", "AUTHORED", "size", "2"));
        var first = service.list("viewer", params);
        Instant anchor = Instant.parse(unpack(first.nextCursor())[4]);
        String created = posts.create(10, "viewer", Map.of("title", "새 글", "content", "새 본문")).postId();
        jdbc.update("UPDATE posts SET created_at=? WHERE id=?", utc(anchor.minus(1, ChronoUnit.MICROS)), Long.parseLong(created));
        params.put("cursor", first.nextCursor());
        assertThat(ids(service.list("viewer", params))).containsExactly("5", "1");
        assertThat(ids(service.list("viewer", Map.of("filter", "AUTHORED"))).getFirst()).isEqualTo(created);
    }

    @Test
    void newReactionsAfterFirstAnchorCannotEnterTraversal() {
        var params = new HashMap<>(Map.of("filter", "LIKED", "size", "2"));
        var first = service.list("viewer", params);
        Instant anchor = Instant.parse(unpack(first.nextCursor())[4]);
        jdbc.update("INSERT INTO post_reactions(post_id,user_id,type,created_at) VALUES(12,1,'LIKE',?)", utc(anchor.plus(1, ChronoUnit.MICROS)));
        reactions.likePost(2, "viewer");
        var actual = new ArrayList<String>();
        params.put("cursor", first.nextCursor());
        for (int page = 0; page < 5; page++) {
            var result = service.list("viewer", params);
            actual.addAll(ids(result));
            if (!result.hasNext()) break;
            params.put("cursor", result.nextCursor());
        }
        assertThat(actual).containsExactly("8", "7", "5", "1");
        // 합성한 미래(상한+1μs) 행은 OS 시계가 다음 tick으로 넘어갈 때까지 새 조회에서도 제외될 수 있다.
        assertThat(ids(service.list("viewer", Map.of("filter", "LIKED")))).startsWith("2");
    }

    @Test
    void deletedCursorBoundaryAndCancelledUnseenReactionDoNotBreakPagination() {
        var authored = service.list("viewer", Map.of("filter", "AUTHORED", "size", "2"));
        posts.delete(6, "viewer");
        assertThat(ids(service.list("viewer", Map.of("filter", "AUTHORED", "size", "2", "cursor", authored.nextCursor()))))
                .containsExactly("5", "1");
        var liked = service.list("viewer", Map.of("filter", "LIKED", "size", "2"));
        reactions.unlikePost(8, "viewer");
        assertThat(ids(service.list("viewer", Map.of("filter", "LIKED", "size", "2", "cursor", liked.nextCursor()))))
                .containsExactly("7", "5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKED", "BOOKMARKED"})
    void cancelAndReregisterMovesActivityToNewTraversalOnly(String filter) {
        var first = service.list("viewer", Map.of("filter", filter, "size", "1"));
        long id = Long.parseLong(first.items().getFirst().postId());
        if (filter.equals("LIKED")) { reactions.unlikePost(id, "viewer"); reactions.likePost(id, "viewer"); }
        else { reactions.unbookmarkPost(id, "viewer"); reactions.bookmarkPost(id, "viewer"); }
        var rest = service.list("viewer", Map.of("filter", filter, "size", "100"));
        assertThat(rest.items().getFirst().postId()).isEqualTo(Long.toString(id));
        var next = service.list("viewer", Map.of("filter", filter, "size", "1", "cursor", first.nextCursor()));
        assertThat(ids(next)).doesNotContain(Long.toString(id));
    }

    @Test
    void reactionApisImmediatelyUpdateOwnListsAndKeepOtherMembersRelations() {
        reactions.bookmarkPost(7, "viewer");
        var first = service.list("viewer", Map.of("filter", "BOOKMARKED"));
        assertThat(first.items().getFirst().postId()).isEqualTo("7");
        assertThat(first.items().getFirst().bookmarkedByMe()).isTrue();
        assertThat(first.items().getFirst().bookmarkCount()).isEqualTo(2);
        reactions.bookmarkPost(7, "viewer");
        assertThat(ids(service.list("viewer", Map.of("filter", "BOOKMARKED")))).isEqualTo(ids(first));
        reactions.unbookmarkPost(7, "viewer");
        assertThat(ids(service.list("viewer", Map.of("filter", "BOOKMARKED")))).doesNotContain("7");
        assertThat(ids(service.list("other", Map.of("filter", "BOOKMARKED")))).contains("7");
        reactions.likePost(12, "viewer");
        assertThat(service.list("viewer", Map.of("filter", "LIKED")).items().getFirst().postId()).isEqualTo("12");
        reactions.unlikePost(12, "viewer");
        assertThat(ids(service.list("viewer", Map.of("filter", "LIKED")))).doesNotContain("12");
    }

    @Test
    void withdrawnAuthorsAreMaskedAndPreviewUsesCodePoints() throws Exception {
        String text = "🏎️".repeat(100) + "끝";
        jdbc.update("UPDATE posts SET content=? WHERE id=11", text);
        var authored = service.list("viewer", Map.of("filter", "AUTHORED"));
        assertThat(authored.items().getFirst().preview().codePointCount(0, authored.items().getFirst().preview().length())).isEqualTo(100);
        assertThat(authored.items().getFirst().thumbnailUrl()).isNull();
        var liked = service.list("viewer", Map.of("filter", "LIKED"));
        assertThat(liked.items().subList(0, 2)).allSatisfy(row -> {
            assertThat(row.author().nickname()).isEqualTo("탈퇴한 사용자");
            assertThat(row.author().avatarAssetId()).isEqualTo("avatar_default");
            assertThat(row.likedByMe()).isTrue();
            assertThat(row.bookmarkedByMe()).isFalse();
        });
    }

    @Test
    void lookaheadDoesNotResolveUnusedThumbnailAndBadVisibleThumbnailFails() throws Exception {
        jdbc.update("""
                INSERT INTO media_files(id,owner_id,object_key,purpose,content_type,size_bytes,width,height,status,
                    post_id,position,created_at,updated_at)
                VALUES(21,1,'../invalid.png','POST_IMAGE','image/png',100,10,10,'ATTACHED',6,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        var first = service.list("viewer", Map.of("filter", "AUTHORED", "size", "1"));
        assertThat(ids(first)).containsExactly("11");
        mvc.perform(get("/api/me/posts").param("filter", "AUTHORED").param("size", "1").param("cursor", first.nextCursor())
                .principal(() -> "viewer")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MEDIA_SERVICE_UNAVAILABLE"));
    }

    @Test
    void repeatedReadsDoNotChangePostsUsersCommentsMediaReactionsOrDedup() {
        var before = snapshot();
        for (int repeat = 0; repeat < 2; repeat++) {
            for (String filter : List.of("AUTHORED", "LIKED", "BOOKMARKED")) service.list("viewer", Map.of("filter", filter));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    protected void insertPost(long id, long authorId, Instant createdAt) {
        jdbc.update("INSERT INTO posts(id,board_id,author_id,title,content,created_at,updated_at) VALUES(?,10,?,'추가 글','추가 본문',?,?)",
                id, authorId, utc(createdAt), utc(createdAt));
    }

    protected static List<String> ids(MyPostsResponse response) { return response.items().stream().map(row -> row.postId()).toList(); }
    protected static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static String[] unpack(String cursor) { return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1); }
    private static String pack(String[] parts) { return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8)); }

    private List<Object> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM posts ORDER BY id"), jdbc.queryForList("SELECT * FROM users ORDER BY id"),
                jdbc.queryForList("SELECT * FROM comments ORDER BY id"), jdbc.queryForList("SELECT * FROM media_files ORDER BY id"),
                jdbc.queryForList("SELECT * FROM post_reactions ORDER BY post_id,user_id,type"),
                jdbc.queryForList("SELECT * FROM post_view_dedup ORDER BY post_id,visitor_hash"));
    }
}
