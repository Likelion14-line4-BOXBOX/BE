package com.boxbox.radio.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.post.PostService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-comment-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/comment-schema.sql",
        "boxbox.posts.media-base-url=https://images.example.test/media"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/comment-cleanup.sql","/db/post-test-data.sql","/db/comment-test-data.sql"})
class CommentApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CommentService service;
    @Autowired protected PostService posts;

    @Test
    void anonymousListHasFlatContractAndMasksDeletedRows() throws Exception {
        mvc.perform(get("/api/posts/1/comments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].commentId").value(is(List.of("1","2","3","4","5","6","7"))))
                .andExpect(jsonPath("$.data.items[0].parentId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].author.userId").value("1"))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].replyCount").value(1))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-01T01:00:00Z"))
                .andExpect(jsonPath("$.data.items[2].isDeleted").value(true))
                .andExpect(jsonPath("$.data.items[2].author").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].content").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].likeCount").value(0))
                .andExpect(jsonPath("$.data.items[3].parentId").value("1"))
                .andExpect(jsonPath("$.data.items[3].replyCount").value(0))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.meta.requestId").isString());
    }

    @Test
    void memberLikesComeFromRelationsAndDeletedRowsRemainMasked() throws Exception {
        mvc.perform(get("/api/posts/1/comments").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[1].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[2].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[4].author").value(nullValue()));
    }

    @Test
    void createRootAndReplyReturn201AndPersistTrimmedContent() throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  새 댓글  \",\"parentId\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.content").value("새 댓글"))
                .andExpect(jsonPath("$.data.parentId").value(nullValue()))
                .andExpect(jsonPath("$.data.author.userId").value("1"))
                .andExpect(jsonPath("$.data.isDeleted").value(false)).andExpect(jsonPath("$.data.likedByMe").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.replyCount").value(0));
        mvc.perform(post("/api/posts/1/comments").principal(() -> "other").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  답글  \",\"parentId\":\"1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.content").value("답글"))
                .andExpect(jsonPath("$.data.parentId").value("1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id=1 AND deleted_at IS NULL",Long.class)).isEqualTo(7);
        assertThat(service.list(1,Map.of(),null).items().getFirst().replyCount()).isEqualTo(2);
    }

    @Test
    void patchOnlyChangesContentAndReturnsContract() throws Exception {
        mvc.perform(patch("/api/comments/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  수정 댓글  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.commentId").value("1"))
                .andExpect(jsonPath("$.data.content").value("수정 댓글")).andExpect(jsonPath("$.data.updatedAt").isString());
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id=1",String.class)).isEqualTo("수정 댓글");
        assertThat(jdbc.queryForObject("SELECT parent_id FROM comments WHERE id=1",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT author_id FROM comments WHERE id=1",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id=1",Long.class)).isEqualTo(2);
    }

    @Test
    void deleteParentKeepsRepliesAndMasksAuthorContentAndLikes() throws Exception {
        mvc.perform(delete("/api/comments/1").principal(() -> "viewer"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var rows=service.list(1,Map.of(),"viewer").items();
        assertThat(rows.getFirst().isDeleted()).isTrue();
        assertThat(rows.getFirst().author()).isNull();
        assertThat(rows.getFirst().content()).isNull();
        assertThat(rows.getFirst().likeCount()).isZero();
        assertThat(rows.getFirst().likedByMe()).isFalse();
        assertThat(rows.getFirst().replyCount()).isEqualTo(1);
        assertThat(rows.stream().filter(row -> "4".equals(row.commentId())).findFirst().orElseThrow().content()).isEqualTo("첫 답글");
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id=1",Long.class)).isEqualTo(2);
        mvc.perform(delete("/api/comments/1").principal(() -> "viewer")).andExpect(status().isNotFound());
    }

    @Test
    void deletingReplyDecreasesDirectReplyAndPostCounts() {
        long before=posts.detail(1,"viewer",null).commentCount();
        service.delete(4,"other");
        assertThat(service.list(1,Map.of(),null).items().getFirst().replyCount()).isZero();
        assertThat(posts.detail(1,"viewer",null).commentCount()).isEqualTo(before-1);
        assertThat(service.list(1,Map.of(),null).items().stream().filter(row -> "4".equals(row.commentId())).findFirst().orElseThrow().isDeleted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"3","5","999"})
    void missingAndDeletedParentsReturn404WithoutInsert(String parent) throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"본문\",\"parentId\":\""+parent+"\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments",Long.class)).isEqualTo(10);
    }

    @Test
    void crossPostParentAndThirdLevelReplyAreRejected() throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"본문\",\"parentId\":\"8\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"본문\",\"parentId\":\"4\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"content\":null}","{\"content\":1}","{\"content\":\"   \"}",
            "{\"content\":\"본문\",\"parentId\":0}","{\"content\":\"본문\",\"parentId\":\"0\"}",
            "{\"content\":\"본문\",\"parentId\":\"abc\"}","{\"content\":\"본문\",\"authorId\":\"2\"}"})
    void invalidCreateBodiesReturn400AndPreserveRows(String body) throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments",Long.class)).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"content\":null}","{\"content\":false}","{\"content\":\" \\n \"}",
            "{\"content\":\"수정\",\"parentId\":null}","{\"content\":\"수정\",\"postId\":\"2\"}",
            "{\"content\":\"수정\",\"authorId\":\"2\"}"})
    void invalidPatchCannotChangeContentOrRelations(String body) throws Exception {
        mvc.perform(patch("/api/comments/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id=1",String.class)).isEqualTo("댓글");
    }

    @ParameterizedTest
    @ValueSource(strings={"null","[]","{broken",""})
    void malformedJsonUsesCommon400(String body) throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void contentLengthUsesCodePointsAndRejects1001() throws Exception {
        var created=service.create(1,"viewer",Map.of("content"," 😀 "));
        assertThat(created.content()).isEqualTo("😀");
        created=service.create(1,"viewer",Map.of("content","😀".repeat(1000)));
        assertThat(created.content()).isEqualTo("😀".repeat(1000));
        mvc.perform(patch("/api/comments/1").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\""+"가".repeat(1001)+"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAndUpdateTimesRoundTripAsUtc() {
        var created=service.create(1,"viewer",Map.of("content","UTC"));
        var stored=jdbc.queryForObject("SELECT created_at FROM comments WHERE id=?",
                (rs,row) -> rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC),Long.parseLong(created.commentId()));
        assertThat(stored).isEqualTo(created.createdAt());
        var updated=service.update(Long.parseLong(created.commentId()),"viewer",Map.of("content","UTC 수정"));
        var listed=service.list(1,Map.of(),"viewer").items().stream().filter(row -> row.commentId().equals(created.commentId())).findFirst().orElseThrow();
        assertThat(listed.updatedAt()).isEqualTo(updated.updatedAt());
    }

    @Test
    void allWritesRequireVerifiedPrincipalAndHeadersCannotImpersonate() throws Exception {
        mvc.perform(post("/api/posts/1/comments").header("X-User-Id","1").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/comments/1").header("Authorization","Bearer viewer").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/comments/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/1/comments").header("Authorization","Bearer viewer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/1/comments").header("X-User-Id","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].likedByMe").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","withdrawn","deleted"})
    void unavailableAccountsCannotWrite(String user) throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> user).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedAccountsAndOtherAuthorsCannotWrite() throws Exception {
        mvc.perform(post("/api/posts/1/comments").principal(() -> "suspended").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("WRITING_RESTRICTED"));
        mvc.perform(get("/api/posts/1/comments").principal(() -> "suspended")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/comments/1").principal(() -> "other").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_COMMENT_AUTHOR"));
        mvc.perform(delete("/api/comments/1").principal(() -> "other")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings={"3","4","999"})
    void deletedInactiveOrMissingPostsAreUnavailable(String id) throws Exception {
        mvc.perform(get("/api/posts/"+id+"/comments")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        mvc.perform(post("/api/posts/"+id+"/comments").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings={"3","5","9","10","999"})
    void deletedOrUnreachableCommentsCannotBeModified(String id) throws Exception {
        mvc.perform(patch("/api/comments/"+id).principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        mvc.perform(delete("/api/comments/"+id).principal(() -> "viewer")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void badIdsReturn400(String id) throws Exception {
        mvc.perform(get("/api/posts/"+id+"/comments")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/comments/"+id).principal(() -> "viewer")).andExpect(status().isBadRequest());
    }

    @Test
    void emptyListAndPageBoundariesHaveCorrectCursorFields() throws Exception {
        var emptyPost=posts.create(10,"viewer",Map.of("title","빈 댓글 글","content","본문"));
        mvc.perform(get("/api/posts/"+emptyPost.postId()+"/comments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        List<String> ids=new ArrayList<>();
        Map<String,String> params=new HashMap<>(Map.of("size","2"));
        for(int page=0;page<10;page++) {
            var result=service.list(1,params,null);
            ids.addAll(result.items().stream().map(row -> row.commentId()).toList());
            if(!result.hasNext()) { assertThat(result.nextCursor()).isNull(); break; }
            assertThat(result.nextCursor()).isNotBlank();
            params.put("cursor",result.nextCursor());
        }
        assertThat(ids).containsExactly("1","2","3","4","5","6","7").doesNotHaveDuplicates();
    }

    @Test
    void cursorFixesInitialRowsEvenWhenNewCommentHasSameTimestamp() throws Exception {
        var first=service.list(1,Map.of("size","2"),null);
        var token=CommentCursor.decode(first.nextCursor(),1,2,Instant.now());
        var added=service.create(1,"viewer",Map.of("content","나중 댓글"));
        jdbc.update("UPDATE comments SET created_at=? WHERE id=?",LocalDateTime.ofInstant(token.anchor(),ZoneOffset.UTC),Long.parseLong(added.commentId()));
        var next=service.list(1,Map.of("size","100"),null);
        assertThat(next.items()).hasSize(8);
        Map<String,String> params=new HashMap<>(Map.of("size","2","cursor",first.nextCursor()));
        List<String> rest=new ArrayList<>();
        for(int page=0;page<10;page++) {
            var result=service.list(1,params,null);
            rest.addAll(result.items().stream().map(row -> row.commentId()).toList());
            if(!result.hasNext()) break;
            params.put("cursor",result.nextCursor());
        }
        assertThat(rest).containsExactly("3","4","5","6","7");
        service.delete(2,"other");
        mvc.perform(get("/api/posts/1/comments").param("size","2").param("cursor",first.nextCursor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].commentId").value("3"));
    }

    @Test
    void cursorCannotMoveToOtherPostOrPageSize() throws Exception {
        var first=service.list(1,Map.of("size","2"),null);
        mvc.perform(get("/api/posts/2/comments").param("size","2").param("cursor",first.nextCursor())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts/1/comments").param("size","3").param("cursor",first.nextCursor())).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"size,0","size,101","size,-1","size,1.5","size,999999999999999","unexpected,1","cursor,invalid"})
    void invalidPageParametersReturn400(String key,String value) throws Exception {
        mvc.perform(get("/api/posts/1/comments").param(key,value)).andExpect(status().isBadRequest());
    }

    @Test
    void malformedCursorFieldsAndFutureTimestampAreRejected() throws Exception {
        for(String cursor:List.of("", "!bad", Base64.getUrlEncoder().encodeToString("1|1|2|7|2999-01-01T00:00:00Z|2026-01-01T00:00:00Z|1".getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            mvc.perform(get("/api/posts/1/comments").param("size","2").param("cursor",cursor)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void listNeverChangesPostsOrCountsViewsAndWithdrawnAuthorHasFallback() throws Exception {
        var before=jdbc.queryForList("SELECT id,view_count,updated_at FROM posts ORDER BY id");
        mvc.perform(get("/api/posts/1/comments")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT id,view_count,updated_at FROM posts ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_view_dedup",Long.class)).isZero();
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=1");
        mvc.perform(get("/api/posts/1/comments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].author.nickname").value("탈퇴한 사용자"));
    }

    @Test
    void concurrentParentDeleteAndReplyPreserveValidThread() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var create=pool.submit(() -> {
                start.await();
                try { service.create(1,"other",Map.of("content","경합 답글","parentId","1")); return "OK"; }
                catch(ApiException exception) { return exception.getCode(); }
            });
            var delete=pool.submit(() -> { start.await(); service.delete(1,"viewer"); return true; });
            start.countDown();
            assertThat(create.get(10,TimeUnit.SECONDS)).isIn("OK","COMMENT_NOT_FOUND");
            assertThat(delete.get(10,TimeUnit.SECONDS)).isTrue();
        }
        var rows=service.list(1,Map.of(),null).items();
        assertThat(rows.getFirst().isDeleted()).isTrue();
        assertThat(rows.getFirst().content()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments r LEFT JOIN comments p ON p.id=r.parent_id AND p.post_id=r.post_id WHERE r.parent_id IS NOT NULL AND p.id IS NULL",Long.class)).isZero();
    }

    @Test
    void concurrentUpdateAndDeleteCannotResurrectComment() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var update=pool.submit(() -> {
                start.await();
                try { service.update(1,"viewer",Map.of("content","경합 수정")); return "OK"; }
                catch(ApiException exception) { return exception.getCode(); }
            });
            var delete=pool.submit(() -> { start.await(); service.delete(1,"viewer"); return true; });
            start.countDown();
            assertThat(update.get(10,TimeUnit.SECONDS)).isIn("OK","COMMENT_NOT_FOUND");
            assertThat(delete.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id=1",String.class)).isNull();
        assertThat(service.list(1,Map.of(),null).items().getFirst().isDeleted()).isTrue();
    }
}
