package com.boxbox.radio.board.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
        "spring.datasource.url=jdbc:h2:mem:boxbox-search-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql",
        "boxbox.posts.media-base-url=https://images.example.test/media"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/post-test-data.sql", "/db/post-search-test-data.sql"})
class PostSearchApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PostSearchService service;
    @Autowired protected PostService posts;

    @BeforeEach
    void setRelativeTimes() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int[] days = {0,9,12,1,1,0,0,0,0,0,2,8,40,400};
        for (int id = 1; id <= 13; id++) {
            Instant created = now.minus(days[id], ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS);
            jdbc.update("UPDATE posts SET created_at=?,updated_at=? WHERE id=?", utc(created), utc(created), id);
        }
    }

    @Test
    void defaultBoardListHasSummaryContractAndExcludesDeletedAndArchivedPosts() throws Exception {
        mvc.perform(get("/api/boards/10/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(7))
                .andExpect(jsonPath("$.data.items[*].postId").value(org.hamcrest.Matchers.is(List.of("6","5","11","1","2","12","13"))))
                .andExpect(jsonPath("$.data.popularPost").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].boardName").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isString());
    }

    @Test
    void globalResponseFlattensSummaryAndAddsBoardNameAndArchiveFlag() throws Exception {
        mvc.perform(get("/api/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(10))
                .andExpect(jsonPath("$.data.items[0].postId").isString())
                .andExpect(jsonPath("$.data.items[0].boardName").value("자유 게시판"))
                .andExpect(jsonPath("$.data.items[0].isArchived").value(false))
                .andExpect(jsonPath("$.data.items[0].title").value("자유 소식"))
                .andExpect(jsonPath("$.data.items[0].summary").doesNotExist())
                .andExpect(jsonPath("$.data.popularPost").doesNotExist());
    }

    @Test
    void archivePreservesOldTagAndOriginalContentWithoutChangingRows() throws Exception {
        mvc.perform(get("/api/posts").param("scope","ARCHIVE").param("q","과거 태그"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].postId").value("7"))
                .andExpect(jsonPath("$.data.items[0].tags[0].name").value("과거 태그"))
                .andExpect(jsonPath("$.data.items[0].preview").value("원문 그대로 보존"))
                .andExpect(jsonPath("$.data.items[0].isArchived").value(true));
        assertThat(jdbc.queryForObject("SELECT content FROM posts WHERE id=7", String.class)).isEqualTo("원문 그대로 보존");
        mvc.perform(get("/api/boards/10/posts").param("tagId","101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void filtersSearchTitleContentAndTagNameAndTrimQuery() throws Exception {
        mvc.perform(get("/api/boards/10/posts").param("q","  레이스  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].postId").value(org.hamcrest.Matchers.is(List.of("6","5"))));
        mvc.perform(get("/api/boards/10/posts").param("q","분석"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(2));
        mvc.perform(get("/api/posts").param("q","검색태그").param("boardId","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].postId").value("11"));
        mvc.perform(get("/api/posts").param("tagId","100").param("boardId","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].postId").value(org.hamcrest.Matchers.is(List.of("5","1"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "100%_!"})
    void sqlWildcardsAreLiteralSearchCharacters(String query) throws Exception {
        mvc.perform(get("/api/posts").param("q",query))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].postId").value("12"));
    }

    @Test
    void queryValuesCannotChangeSqlPredicates() throws Exception {
        mvc.perform(get("/api/posts").param("q","' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @ParameterizedTest
    @CsvSource({"DAY,2", "WEEK,2", "MONTH,5", "YEAR,6", "ALL,7"})
    void periodsFilterFromCurrentTime(String period, int count) throws Exception {
        mvc.perform(get("/api/boards/10/posts").param("period",period))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(count));
    }

    @Test
    void popularPreviewUsesLikeCountThenNewestAndIdWithinSameFilters() throws Exception {
        mvc.perform(get("/api/boards/20/posts").param("sort","OLDEST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].postId").value(org.hamcrest.Matchers.is(List.of("8","9"))))
                .andExpect(jsonPath("$.data.popularPost.postId").value("9"));
        mvc.perform(get("/api/boards/20/posts").param("tagId","200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.popularPost.postId").value("8"));
        mvc.perform(get("/api/boards/30/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.popularPost.postId").value("10"));
        mvc.perform(get("/api/boards/20/posts").param("q","일치 없음"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.popularPost").value(nullValue()));
    }

    @Test
    void summaryCountsIgnoreDeletedCommentsAndUseMemberRelationsAndStoredImageOrder() throws Exception {
        mvc.perform(get("/api/boards/10/posts").param("q","기존 글").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].author.nickname").value("작성자"))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.items[0].commentCount").value(2))
                .andExpect(jsonPath("$.data.items[0].bookmarkCount").value(1))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[0].bookmarkedByMe").value(false))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://images.example.test/media/posts/%EA%B0%80%20%EB%82%98.png"));
        mvc.perform(get("/api/posts").param("q","기존 글").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].bookmarkedByMe").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST","OLDEST","POPULAR","COMMENTS"})
    void cursorVisitsEveryItemExactlyOnceIncludingTies(String sort) {
        var params = new HashMap<>(Map.of("sort",sort,"size","2"));
        List<String> expected = service.boardPosts(filter(Map.of("sort",sort)), null).items().stream()
                .map(item -> item.postId()).toList();
        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 10; page++) {
            var result = service.boardPosts(filter(params), null);
            assertThat(result.totalCount()).isEqualTo(7);
            collected.addAll(result.items().stream().map(item -> item.postId()).toList());
            if (!result.hasNext()) { assertThat(result.nextCursor()).isNull(); break; }
            assertThat(result.nextCursor()).isNotBlank();
            params.put("cursor", result.nextCursor());
        }
        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void popularAndCommentSortHaveCorrectScoreAndTieOrder() {
        assertThat(service.boardPosts(filter(Map.of("sort","POPULAR")), null).items().stream().map(item -> item.postId()).toList())
                .containsExactly("5","6","1","11","2","12","13");
        assertThat(service.boardPosts(filter(Map.of("sort","COMMENTS")), null).items().stream().map(item -> item.postId()).toList())
                .containsExactly("6","1","5","11","2","12","13");
    }

    @Test
    void globalCursorCanContinueArchiveWithSameScope() throws Exception {
        jdbc.update("UPDATE posts SET tag_id=101 WHERE id=5");
        var first = service.search(PostSearchFilter.from(null,Map.of("scope","ARCHIVE","size","1")),null);
        assertThat(first.totalCount()).isEqualTo(2);
        mvc.perform(get("/api/posts").param("scope","ARCHIVE").param("size","1").param("cursor",first.nextCursor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].postId").value("5"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void cursorKeepsInitialTimeWindowAndDoesNotIncludeNewPosts() {
        var first = service.boardPosts(filter(Map.of("size","1","period","DAY")),null);
        var created = posts.create(10,"viewer",Map.of("title","나중 글","content","나중 본문"));
        var cursor = PostSearchCursor.decode(first.nextCursor(),filter(Map.of("size","1","period","DAY")),Instant.now());
        // 동일 시각으로 저장되거나 나중에 시각을 과거로 입력해도 새 ID는 첫 조회 상한을 넘는다.
        jdbc.update("UPDATE posts SET created_at=? WHERE id=?",utc(cursor.anchor()),Long.parseLong(created.postId()));
        var params = new HashMap<>(Map.of("size","1","period","DAY","cursor",first.nextCursor()));
        var next = service.boardPosts(filter(params),null);
        assertThat(next.totalCount()).isEqualTo(2);
        assertThat(next.items().getFirst().postId()).isEqualTo("5");
        assertThat(next.hasNext()).isFalse();
    }

    @Test
    void deletingCursorBoundaryDoesNotInvalidateNextPage() {
        var first = service.boardPosts(filter(Map.of("size","2")),null);
        posts.delete(5,"viewer");
        var next = service.boardPosts(filter(Map.of("size","2","cursor",first.nextCursor())),null);
        assertThat(next.items().stream().map(item -> item.postId()).toList()).containsExactly("11","1");
        assertThat(next.totalCount()).isEqualTo(6);
    }

    @ParameterizedTest
    @CsvSource({"sort,OLDEST", "period,DAY", "q,레이스", "tagId,100", "size,3"})
    void cursorCannotBeReusedWithChangedFilters(String key, String value) throws Exception {
        var first = service.boardPosts(filter(Map.of("size","2")),null);
        var request = get("/api/boards/10/posts").param("cursor",first.nextCursor());
        if (!key.equals("size")) request.param("size","2");
        mvc.perform(request.param(key,value)).andExpect(status().isBadRequest());
    }

    @Test
    void cursorCannotMoveToDifferentEndpointOrBoardOrScope() throws Exception {
        var first = service.boardPosts(filter(Map.of("size","2")),null);
        mvc.perform(get("/api/boards/20/posts").param("size","2").param("cursor",first.nextCursor()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("boardId","10").param("size","2").param("cursor",first.nextCursor()))
                .andExpect(status().isBadRequest());
        var global = service.search(PostSearchFilter.from(null,Map.of("size","2")),null);
        mvc.perform(get("/api/posts").param("scope","ARCHIVE").param("size","2").param("cursor",global.nextCursor()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "!bad-base64!", "YXxifGM", "MQ"})
    void malformedCursorsReturnCommon400(String cursor) throws Exception {
        mvc.perform(get("/api/posts").param("cursor",cursor)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @CsvSource({"sort,NEWEST", "period,TODAY", "scope,ALL", "size,0", "size,101", "size,1.5", "size,-1", "size,99999999999999999",
            "boardId,0", "boardId,abc", "tagId,-1", "tagId,abc", "unexpected,1"})
    void invalidSearchParametersReturnCommon400(String key,String value) throws Exception {
        mvc.perform(get("/api/posts").param(key,value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidQueryLengthIsRejected(String query) throws Exception {
        mvc.perform(get("/api/posts").param("q",query)).andExpect(status().isBadRequest());
    }

    @Test
    void unicodeQueryLengthUsesCodePointsAndPreviewDoesNotSplitSurrogates() throws Exception {
        mvc.perform(get("/api/posts").param("q","😀".repeat(100))).andExpect(status().isOk());
        mvc.perform(get("/api/posts").param("q","😀".repeat(101))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE posts SET content=? WHERE id=5", "😀".repeat(101));
        var item = service.boardPosts(filter(Map.of("tagId","100")),null).items().getFirst();
        assertThat(item.preview()).isEqualTo("😀".repeat(100));
    }

    @Test
    void emptyBoardAndUnmatchedFiltersHaveEmptyArraysAndNullCursors() throws Exception {
        for (String uri : List.of("/api/boards/40/posts","/api/posts?q=unmatched")) {
            mvc.perform(get(uri)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.totalCount").value(0)).andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"50","999"})
    void missingAndInactiveBoardsReturn404(String board) throws Exception {
        mvc.perform(get("/api/boards/"+board+"/posts")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
        mvc.perform(get("/api/posts").param("boardId",board)).andExpect(status().isNotFound());
    }

    @Test
    void unrelatedAndMissingTagFiltersAreInvalid() throws Exception {
        mvc.perform(get("/api/boards/10/posts").param("tagId","200")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("tagId","999")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing","suspended","withdrawn","deleted"})
    void unavailableMembersAreNotTreatedAsAnonymous(String user) throws Exception {
        mvc.perform(get("/api/posts").principal(() -> user)).andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedBearerIsRejectedAndFakeUserHeaderDoesNotPersonalize() throws Exception {
        mvc.perform(get("/api/posts").header("Authorization","Bearer viewer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/boards/10/posts").param("q","기존 글").header("X-User-Id","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].likedByMe").value(nullValue()));
    }

    @Test
    void listAndPopularPreviewDoNotIncrementViewsOrCreateDedupRecords() throws Exception {
        var before = jdbc.queryForList("SELECT id,view_count,updated_at FROM posts ORDER BY id");
        mvc.perform(get("/api/posts")).andExpect(status().isOk());
        mvc.perform(get("/api/boards/20/posts")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT id,view_count,updated_at FROM posts ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_view_dedup",Long.class)).isZero();
    }

    @Test
    void withdrawnAuthorsUseSamePublicFallbackAsDetail() throws Exception {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=1");
        mvc.perform(get("/api/posts").param("q","기존 글"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].author.nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.items[0].author.avatarAssetId").value("avatar_default"));
    }

    @Test
    void badThumbnailInLookaheadDoesNotBreakCurrentTextOnlyPage() throws Exception {
        jdbc.update("UPDATE media_files SET object_key='../bad' WHERE id=10");
        mvc.perform(get("/api/boards/10/posts").param("size","3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/api/boards/10/posts").param("q","기존 글"))
                .andExpect(status().isServiceUnavailable());
    }

    private static PostSearchFilter filter(Map<String,String> params) { return PostSearchFilter.from(10L,params); }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
}
