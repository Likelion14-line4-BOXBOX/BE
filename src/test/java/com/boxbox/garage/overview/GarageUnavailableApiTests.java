package com.boxbox.garage.overview;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 런타임과 같이 마켓 provider Bean을 등록하지 않은 컨텍스트다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-garage-no-market-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/garage-cleanup.sql", "/db/profile-test-data.sql", "/db/garage-test-data.sql"})
class GarageUnavailableApiTests {
    @Autowired MockMvc mvc;

    @Test void absentProviderIs503ForOwnerAndPublicButPrivateOtherStillReturns200() throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"));
        mvc.perform(get("/api/users/2/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"));
        mvc.perform(get("/api/users/1/garage").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.stampSummary.watchedCount").value(1));
    }
}
