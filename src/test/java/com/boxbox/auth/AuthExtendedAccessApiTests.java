package com.boxbox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-extended;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql",
        "boxbox.auth.extended-access-user-ids=42",
        "boxbox.auth.extended-access-lifetime=P10D"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthApiTests.TimeConfiguration.class)
@Sql("/db/auth-cleanup.sql")
class AuthExtendedAccessApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired AuthApiTests.MutableClock clock;

    @Test void loginRefreshAndProtectedApiUsePerUserLifetimeWithoutExtendingRefreshSession() throws Exception {
        jdbc.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 42");
        signup("hamiltonlove", "extended");
        signup("ordinary", "ordinary");
        var extended = login("hamiltonlove", 864000);
        var ordinary = login("ordinary", 900);
        assertThat(extended.getResponse().getHeader("Set-Cookie")).contains("Max-Age=1209600");
        clock.advance(Duration.ofDays(1));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token(extended))).andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token(ordinary))).andExpect(status().isUnauthorized());
        var refreshed = refresh(extended, 864000);
        assertThat(refreshed.getResponse().getHeader("Set-Cookie")).contains("Max-Age=1123200");
        refresh(ordinary, 900);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token(refreshed))).andExpect(status().isOk());
    }
    void signup(String id, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"test-only-password\",\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated());
    }
    MvcResult login(String id, long expiry) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"test-only-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(expiry)).andReturn();
    }
    MvcResult refresh(MvcResult login, long expiry) throws Exception {
        String value = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0].substring("refreshToken=".length());
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", value))
                .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(expiry)).andReturn();
    }
    String token(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asString();
    }
}
