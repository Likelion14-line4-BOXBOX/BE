-- 공통 teams / drivers 스키마를 먼저 적용한다. users / posts도 조회 시 필요하다.
-- 이 파일은 boards / board_tags만 생성한다. 애플리케이션 시작 시 자동 실행하지 않는다.
CREATE TABLE IF NOT EXISTS boards (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    type VARCHAR(16) NOT NULL,
    team_id BIGINT UNSIGNED NULL,
    name VARCHAR(100) NOT NULL,
    display_order SMALLINT UNSIGNED NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_boards_1 (code),
    UNIQUE KEY uq_boards_2 (team_id),
    CONSTRAINT fk_boards_1 FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT ck_boards_type CHECK (type IN ('TEAM', 'FREE', 'BEGINNER')),
    CONSTRAINT ck_boards_team CHECK (
        (type = 'TEAM' AND team_id IS NOT NULL)
        OR (type IN ('FREE', 'BEGINNER') AND team_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS board_tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    board_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(80) NOT NULL,
    tag_group VARCHAR(16) NOT NULL,
    driver_id BIGINT UNSIGNED NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order SMALLINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_board_tags_1 (board_id, name),
    UNIQUE KEY uq_board_tags_2 (board_id, id),
    CONSTRAINT fk_board_tags_1 FOREIGN KEY (board_id) REFERENCES boards (id),
    CONSTRAINT fk_board_tags_2 FOREIGN KEY (driver_id) REFERENCES drivers (id),
    CONSTRAINT ck_board_tags_group CHECK (tag_group IN ('TEAM', 'DRIVER', 'TOPIC'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
