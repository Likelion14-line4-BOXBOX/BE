-- 조회에 필요한 공통 테이블은 테스트 전용 최소 스키마다. 운영 마이그레이션이 아니다.
CREATE TABLE IF NOT EXISTS teams (
    id BIGINT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    display_order SMALLINT NOT NULL
);
CREATE TABLE IF NOT EXISTS drivers (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    team_id BIGINT REFERENCES teams(id)
);
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    login_id VARCHAR(20) NOT NULL UNIQUE,
    favorite_team_id BIGINT REFERENCES teams(id),
    status VARCHAR(16) NOT NULL,
    deleted_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS boards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    type VARCHAR(16) NOT NULL,
    team_id BIGINT UNIQUE REFERENCES teams(id),
    name VARCHAR(100) NOT NULL,
    display_order SMALLINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (type IN ('TEAM', 'FREE', 'BEGINNER')),
    CHECK ((type = 'TEAM' AND team_id IS NOT NULL)
        OR (type IN ('FREE', 'BEGINNER') AND team_id IS NULL))
);
CREATE TABLE IF NOT EXISTS board_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    board_id BIGINT NOT NULL REFERENCES boards(id),
    name VARCHAR(80) NOT NULL,
    tag_group VARCHAR(16) NOT NULL,
    driver_id BIGINT REFERENCES drivers(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order SMALLINT NOT NULL,
    UNIQUE (board_id, name),
    CHECK (tag_group IN ('TEAM', 'DRIVER', 'TOPIC'))
);
CREATE TABLE IF NOT EXISTS posts (
    id BIGINT PRIMARY KEY,
    board_id BIGINT NOT NULL REFERENCES boards(id),
    deleted_at TIMESTAMP
);
