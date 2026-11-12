package com.boxbox.board;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/db/board-test-data.sql")
class BoardApiTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousBoardsUseDatabaseOrderAndExcludeDeletedPosts() throws Exception {
        mvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[*].boardId").value(is(java.util.List.of("20", "10", "30", "40"))))
                .andExpect(jsonPath("$.data.items[0].postCount").value(0))
                .andExpect(jsonPath("$.data.items[1].postCount").value(2))
                .andExpect(jsonPath("$.data.items[2].postCount").value(1))
                .andExpect(jsonPath("$.data.items[2].teamId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[*].isMyTeam").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andExpect(jsonPath("$.meta.serverTime").isString());
    }

    @ParameterizedTest
    @CsvSource({"TEAM,2", "FREE,1", "BEGINNER,1"})
    void filtersByBoardType(String type, int count) throws Exception {
        mvc.perform(get("/api/boards").param("type", type))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(count))
                .andExpect(jsonPath("$.data.items[*].type").value(everyItem(is(type))));
    }

    @Test
    void filtersByTeamWithOrWithoutExplicitTeamType() throws Exception {
        for (String type : new String[]{"", "TEAM"}) {
            var request = get("/api/boards").param("teamId", "1");
            if (!type.isEmpty()) {
                request.param("type", type);
            }
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].boardId").value("10"))
                    .andExpect(jsonPath("$.data.items[0].teamId").value("1"));
        }
    }

    @Test
    void unmatchedTeamReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/boards").param("teamId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void authenticatedPrincipalUsesStoredFavoriteTeam() throws Exception {
        mvc.perform(get("/api/boards").principal(() -> "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].isMyTeam").value(false))
                .andExpect(jsonPath("$.data.items[1].isMyTeam").value(true))
                .andExpect(jsonPath("$.data.items[2].isMyTeam").value(false))
                .andExpect(jsonPath("$.data.items[3].isMyTeam").value(false));
    }

    @Test
    void memberWithoutFavoriteTeamHasNoMyTeam() throws Exception {
        mvc.perform(get("/api/boards").principal(() -> "no_favorite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].isMyTeam").value(everyItem(is(false))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "suspended", "deleted", "withdrawn", ""})
    void unavailableMembersAreNotTreatedAsAnonymous(String loginId) throws Exception {
        mvc.perform(get("/api/boards").principal(() -> loginId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/boards", "/api/boards/10/tags"})
    void unverifiedAuthorizationHeaderIsRejected(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer viewer"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void userIdHeaderCannotImpersonateMember() throws Exception {
        mvc.perform(get("/api/boards").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].isMyTeam").value(everyItem(nullValue())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"team", "DRIVER", "", " TEAM"})
    void rejectsInvalidBoardType(String type) throws Exception {
        mvc.perform(get("/api/boards").param("type", type))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "1.5", "abc", "", "9223372036854775808"})
    void rejectsInvalidTeamId(String teamId) throws Exception {
        mvc.perform(get("/api/boards").param("teamId", teamId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FREE", "BEGINNER"})
    void rejectsTeamFilterOnOtherBoardTypes(String type) throws Exception {
        mvc.perform(get("/api/boards").param("type", type).param("teamId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void tagsAreScopedOrderedAndPreserveInactiveFlag() throws Exception {
        mvc.perform(get("/api/boards/10/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[*].tagId").value(is(java.util.List.of("100", "101", "103", "102"))))
                .andExpect(jsonPath("$.data.items[0].group").value("TEAM"))
                .andExpect(jsonPath("$.data.items[1].group").value("DRIVER"))
                .andExpect(jsonPath("$.data.items[2].group").value("TOPIC"))
                .andExpect(jsonPath("$.data.items[0].isActive").value(true))
                .andExpect(jsonPath("$.data.items[3].isActive").value(false));
    }

    @Test
    void boardWithoutTagsReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/boards/40/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void authenticatedMemberCanReadTags() throws Exception {
        mvc.perform(get("/api/boards/10/tags").principal(() -> "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4));
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 50})
    void missingOrInactiveBoardReturnsNotFound(long boardId) throws Exception {
        mvc.perform(get("/api/boards/{boardId}/tags", boardId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-board", "9223372036854775808"})
    void rejectsInvalidBoardId(String boardId) throws Exception {
        mvc.perform(get("/api/boards/{boardId}/tags", boardId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
