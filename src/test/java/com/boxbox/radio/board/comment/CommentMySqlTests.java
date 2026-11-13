package com.boxbox.radio.board.comment;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 전체 ERD를 적용한 로컬 댓글 검증 전용 DB만 사용한다. */
@EnabledIfEnvironmentVariable(named="RUN_COMMENT_MYSQL_TESTS",matches="true")
class CommentMySqlTests extends CommentApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_comment_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username",() -> System.getenv().getOrDefault("COMMENT_MYSQL_USER","root"));
        registry.add("spring.datasource.password",() -> System.getenv().getOrDefault("COMMENT_MYSQL_PASSWORD",""));
        registry.add("spring.sql.init.mode",() -> "never");
    }
}
