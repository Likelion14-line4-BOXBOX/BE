package com.boxbox.board;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql("/db/board-test-data.sql")
class BoardSeedTests {
    @Autowired
    private DataSource dataSource;

    @Test
    void seedUsesExistingReferenceDataAndCanRunTwiceWithoutOverwriting() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM board_tags");
        jdbc.update("DELETE FROM boards");
        var seed = new ResourceDatabasePopulator(new ClassPathResource("db/board-seed.sql"));

        seed.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM boards", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board_tags", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board_tags WHERE tag_group = 'DRIVER'", Integer.class))
                .isEqualTo(2);

        jdbc.update("UPDATE boards SET name = '운영 변경 이름' WHERE team_id = 1");
        seed.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM boards", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board_tags", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT name FROM boards WHERE team_id = 1", String.class))
                .isEqualTo("운영 변경 이름");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teams", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drivers", Integer.class)).isEqualTo(2);
    }
}
