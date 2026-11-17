package com.boxbox.radio.report;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-report-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/comment-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql,classpath:db/report-schema.sql"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/report-cleanup.sql", "/db/post-test-data.sql", "/db/comment-test-data.sql", "/db/report-chat-test-data.sql"})
@Sql(scripts = "/db/report-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ReportApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ReportService reports;
    @Autowired protected ReportRepository repository;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected JsonMapper json;

    @ParameterizedTest @CsvSource({"POST,post_id", "COMMENT,comment_id", "CHAT_MESSAGE,message_id"})
    void createsTypedReceiptAndOnlySelectedTargetColumn(String type, String column) throws Exception {
        var before = snapshot();
        Instant start = Instant.now().minusSeconds(1);
        var result = mvc.perform(post("/api/reports").principal(() -> "other").contentType("application/json")
                .content(json.writeValueAsString(body(type, "1", "SPAM"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data.reportId").isString()).andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("data").path("reportId").asString();
        var stored = jdbc.queryForMap("SELECT * FROM reports WHERE id=?", id);
        assertThat(((Number) stored.get("reporter_id")).longValue()).isEqualTo(2);
        assertThat(stored.get("target_type")).isEqualTo(type);
        assertThat(((Number) stored.get(column)).longValue()).isEqualTo(1);
        for (String target : List.of("post_id", "comment_id", "message_id")) if (!target.equals(column)) assertThat(stored.get(target)).isNull();
        assertThat(stored.get("reason")).isEqualTo("SPAM");
        assertThat(stored.get("description")).isNull();
        assertThat(stored.get("status")).isEqualTo("RECEIVED");
        Instant created = jdbc.queryForObject("SELECT created_at FROM reports WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), id);
        assertThat(created).isBetween(start, Instant.now().plusSeconds(1));
        assertThat(stored.get("created_at")).isEqualTo(stored.get("updated_at"));
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"SPAM", "ABUSE", "INAPPROPRIATE", "OTHER"})
    void supportsEachReasonAndTrimsDescription(String reason) {
        var input = body("POST", "1", reason); input.put("description", "\u2003 상세 사유 \n");
        var receipt = reports.create("other", input);
        assertThat(jdbc.queryForObject("SELECT description FROM reports WHERE id=?", String.class, receipt.reportId())).isEqualTo("상세 사유");
    }

    @Test void differentReasonsAndReportersAndTargetTypesAreIndependent() {
        for (String reason : List.of("SPAM", "ABUSE", "INAPPROPRIATE", "OTHER")) {
            var input = body("POST", "1", reason); input.put("description", "설명");
            reports.create("viewer", input);
        }
        reports.create("other", body("POST", "1", "SPAM"));
        reports.create("viewer", body("COMMENT", "1", "SPAM"));
        reports.create("viewer", body("CHAT_MESSAGE", "1", "SPAM"));
        assertThat(count()).isEqualTo(7);
    }

    @Test void duplicateDoesNotReplaceReasonDescriptionStatusOrTime() throws Exception {
        var first = body("POST", "1", "SPAM"); first.put("description", "최초 설명");
        var receipt = reports.create("other", first);
        jdbc.update("UPDATE reports SET status='RESOLVED' WHERE id=?", receipt.reportId());
        var stored = jdbc.queryForList("SELECT * FROM reports");
        first.put("description", "다른 설명");
        mvc.perform(post("/api/reports").principal(() -> "other").contentType("application/json").content(json.writeValueAsString(first)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALREADY_REPORTED"));
        assertThat(jdbc.queryForList("SELECT * FROM reports")).isEqualTo(stored);
    }

    @Test void numericIdFormattingCannotBypassDuplicateConstraint() {
        reports.create("viewer", body("POST", "0001", "SPAM"));
        assertThatThrownBy(() -> reports.create("viewer", body("POST", "1", "SPAM")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_REPORTED"));
    }

    @Test void databaseUniqueConstraintAlsoMapsToConflictForExternalDuplicate() {
        var input = ReportInput.from(body("POST", "1", "SPAM"));
        reports.create("viewer", body("POST", "1", "SPAM"));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> repository.insert(1, input, Instant.now())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_REPORTED"));
        assertThat(count()).isEqualTo(1);
    }

    @Test void acceptsArchivedMessageAndInactiveTagPostAndLiveReplyUnderDeletedParent() {
        jdbc.update("UPDATE posts SET tag_id=101 WHERE id=2");
        jdbc.update("UPDATE comments SET content=NULL,deleted_at=CURRENT_TIMESTAMP WHERE id=1");
        reports.create("viewer", body("POST", "2", "SPAM"));
        reports.create("viewer", body("CHAT_MESSAGE", "3", "SPAM"));
        reports.create("viewer", body("COMMENT", "4", "SPAM"));
        assertThat(count()).isEqualTo(3);
    }

    @Test void contentByWithdrawnAuthorCanStillBeReported() {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=2");
        reports.create("viewer", body("POST", "2", "SPAM"));
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"POST,999", "POST,3", "POST,4", "COMMENT,999", "COMMENT,3", "COMMENT,9", "COMMENT,10", "CHAT_MESSAGE,999", "CHAT_MESSAGE,2"})
    void missingDeletedOrUnavailableTargetsReturn404WithoutMutation(String type, String id) throws Exception {
        var before = snapshot();
        mvc.perform(post("/api/reports").principal(() -> "other").contentType("application/json")
                .content(json.writeValueAsString(body(type, id, "SPAM")))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_TARGET_NOT_FOUND"));
        assertThat(count()).isZero();
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test void deletingReportedTargetPreservesReportAndFurtherReportReturns404() {
        reports.create("other", body("POST", "1", "SPAM"));
        jdbc.update("UPDATE posts SET content=NULL,deleted_at=CURRENT_TIMESTAMP WHERE id=1");
        assertThatThrownBy(() -> reports.create("other", body("POST", "1", "SPAM")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_TARGET_NOT_FOUND"));
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void invalidInputsAre400AndNeverStoreReport(String name, Map<String,Object> input) throws Exception {
        mvc.perform(post("/api/reports").principal(() -> "viewer").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(count()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        var values = new java.util.ArrayList<Arguments>();
        for (String field : List.of("targetType", "targetId", "reason")) {
            var input = body("POST", "1", "SPAM"); input.remove(field); values.add(Arguments.of("missing " + field, input));
            input = body("POST", "1", "SPAM"); input.put(field, null); values.add(Arguments.of("null " + field, input));
        }
        for (Object value : List.of("post", "UNKNOWN", "", 1)) values.add(Arguments.of("target type " + value, replace("targetType", value)));
        for (Object value : List.of("p001", "0", "-1", "1.0", "1e2", " 1", "", "9223372036854775808", 1, true)) values.add(Arguments.of("target ID " + value, replace("targetId", value)));
        for (Object value : List.of("spam", "UNKNOWN", "", 1)) values.add(Arguments.of("reason " + value, replace("reason", value)));
        for (Object value : java.util.Arrays.asList(null, 1, List.of("reason"), "x".repeat(1001), "😀".repeat(1001))) values.add(Arguments.of("invalid description", replace("description", value)));
        for (String value : List.of("", "   ", "\u2003\n")) {
            var input = body("POST", "1", "OTHER"); input.put("description", value); values.add(Arguments.of("blank OTHER", input));
        }
        values.add(Arguments.of("missing OTHER description", body("POST", "1", "OTHER")));
        for (String field : List.of("reporterId", "status", "postId")) values.add(Arguments.of("unsupported " + field, replace(field, "1")));
        return values.stream();
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "null", "{", "{}", "{\"targetType\":\"POST\",\"targetId\":\"1\",\"reason\":\"SPAM\",\"description\":\"\\ud800\"}"})
    void malformedBodiesOrInvalidUnicodeAre400(String input) throws Exception {
        mvc.perform(post("/api/reports").principal(() -> "viewer").contentType("application/json").content(input))
                .andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test void unicodeLimitAllows1000EmojiAndEmptyOptionalDescriptionStoresNull() {
        var input = body("POST", "1", "OTHER"); input.put("description", "  " + "😀".repeat(1000) + "\n");
        var receipt = reports.create("viewer", input);
        assertThat(jdbc.queryForObject("SELECT description FROM reports WHERE id=?", String.class, receipt.reportId())).isEqualTo("😀".repeat(1000));
        input = body("POST", "1", "SPAM"); input.put("description", "\u2003\n");
        receipt = reports.create("viewer", input);
        assertThat(jdbc.queryForMap("SELECT description FROM reports WHERE id=?", receipt.reportId()).get("description")).isNull();
    }

    @ParameterizedTest @CsvSource({"missing,401", "suspended,403", "withdrawn,401", "deleted,401"})
    void accountStateIsCheckedBeforeCreatingReport(String loginId, int expected) throws Exception {
        var before = snapshot();
        mvc.perform(post("/api/reports").principal(() -> loginId).contentType("application/json").content(json.writeValueAsString(body("POST", "1", "SPAM"))))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.error.code").value(expected == 403 ? "REPORTING_RESTRICTED" : "UNAUTHORIZED"));
        assertThat(count()).isZero();
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test void anonymousForgedHeadersAndQueryCannotChooseReporter() throws Exception {
        String input = json.writeValueAsString(body("POST", "1", "SPAM"));
        mvc.perform(post("/api/reports").contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reports").header("X-User-Id", "1").contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reports").header("Authorization", "Bearer viewer").contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reports?userId=1").principal(() -> "other").contentType("application/json").content(input)).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test void concurrentIdenticalReportsCreateOnlyOneReceipt() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 10).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { reports.create("other", body("POST", "1", "SPAM")); return "CREATED"; }
                catch (ApiException error) { return error.getCode(); }
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<String>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn("CREATED"::equals).hasSize(1);
            assertThat(results).filteredOn("ALREADY_REPORTED"::equals).hasSize(9);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test void failedInsertRollsBackAndNeverChangesTargetOrParticipation() {
        var before = snapshot();
        jdbc.execute("ALTER TABLE reports ADD CONSTRAINT reject_spam_report CHECK(reason<>'SPAM')");
        try {
            assertThatThrownBy(() -> reports.create("other", body("CHAT_MESSAGE", "1", "SPAM")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count()).isZero();
            assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
        } finally {
            boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("MySQL"));
            jdbc.execute("ALTER TABLE reports DROP " + (mysql ? "CHECK" : "CONSTRAINT") + " reject_spam_report");
        }
    }

    @Test void surroundingTransactionRollbackRemovesReceipt() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            reports.create("other", body("COMMENT", "1", "ABUSE"));
            assertThat(count()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
    }

    protected long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM reports", Long.class); }
    protected static Map<String,Object> body(String type, String id, String reason) {
        return new HashMap<>(Map.of("targetType", type, "targetId", id, "reason", reason));
    }
    private static Map<String,Object> replace(String field, Object value) { var input = body("POST", "1", "SPAM"); input.put(field, value); return input; }
    private Map<String, List<Map<String,Object>>> snapshot() {
        var data = new LinkedHashMap<String, List<Map<String,Object>>>();
        for (String table : List.of("users", "boards", "board_tags", "posts", "comments", "media_files", "post_reactions", "comment_likes",
                "post_view_dedup", "races", "race_sessions", "chat_rooms", "chat_participants", "chat_messages", "stream_events")) {
            String order = switch (table) {
                case "post_reactions" -> "post_id,user_id,type";
                case "comment_likes" -> "comment_id,user_id";
                case "post_view_dedup" -> "post_id,visitor_hash";
                case "chat_participants" -> "room_id,user_id";
                default -> "id";
            };
            data.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + order));
        }
        return data;
    }
}
