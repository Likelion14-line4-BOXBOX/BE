-- 조회에 필요한 공통 테이블은 테스트 전용 최소 스키마다. 운영 마이그레이션이 아니다.
CREATE TABLE IF NOT EXISTS teams (
    id BIGINT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    display_order SMALLINT NOT NULL,
    championship_rank SMALLINT,
    stats_season_year SMALLINT,
    stats_as_of TIMESTAMP
);
CREATE TABLE IF NOT EXISTS drivers (
    id BIGINT PRIMARY KEY,
    code VARCHAR(40) UNIQUE,
    name VARCHAR(100) NOT NULL,
    team_id BIGINT REFERENCES teams(id),
    car_number SMALLINT,
    championship_rank SMALLINT,
    stats_season_year SMALLINT,
    stats_as_of TIMESTAMP
);
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    login_id VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL DEFAULT 'test-only-hash',
    nickname VARCHAR_IGNORECASE(20) NOT NULL UNIQUE,
    bio VARCHAR(100) NOT NULL DEFAULT '',
    avatar_asset_id VARCHAR(80) NOT NULL DEFAULT 'avatar_default',
    favorite_team_id BIGINT REFERENCES teams(id),
    favorite_driver_id BIGINT REFERENCES drivers(id),
    show_market_assets BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    coin_balance BIGINT NOT NULL DEFAULT 0,
    wallet_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    deleted_at TIMESTAMP,
    CHECK (CHAR_LENGTH(nickname) BETWEEN 2 AND 20),
    CHECK (favorite_driver_id IS NULL OR favorite_team_id IS NOT NULL)
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
