package com.boxbox.garage.overview;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import com.boxbox.market.portfolio.MarketPortfolioService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 adapter를 사용하고 마켓 읽기 장애만 주입한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-garage-no-market-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/garage-cleanup.sql", "/db/profile-test-data.sql", "/db/garage-test-data.sql"})
class GarageUnavailableApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean MarketPortfolioService portfolio;

    @Test void marketReadFailureIs503ForOwnerAndPublicButPrivateOtherStillReturns200() throws Exception {
        when(portfolio.summary(anyLong(), any())).thenThrow(new DataAccessResourceFailureException("test source unavailable"));
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"));
        mvc.perform(get("/api/users/2/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"));
        clearInvocations(portfolio);
        mvc.perform(get("/api/users/1/garage").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.stampSummary.watchedCount").value(1));
        verifyNoInteractions(portfolio);
    }
}
