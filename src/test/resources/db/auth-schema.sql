-- 인증 테스트 전용 H2 스키마. 운영 DDL은 기존 ERD를 사용한다.
CREATE TABLE IF NOT EXISTS teams(id BIGINT PRIMARY KEY, name VARCHAR(100), championship_rank SMALLINT);
CREATE TABLE IF NOT EXISTS drivers(id BIGINT PRIMARY KEY, name VARCHAR(100), team_id BIGINT, car_number SMALLINT, championship_rank SMALLINT);
CREATE TABLE IF NOT EXISTS users(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_id VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR_IGNORECASE(20) NOT NULL UNIQUE,
    bio VARCHAR(100) NOT NULL DEFAULT '',
    avatar_asset_id VARCHAR(80) NOT NULL DEFAULT 'avatar_default',
    favorite_team_id BIGINT,
    favorite_driver_id BIGINT,
    show_market_assets BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    coin_balance BIGINT NOT NULL DEFAULT 0,
    wallet_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS coin_ledger(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    event_key VARCHAR(160) NOT NULL,
    entry_type VARCHAR(24) NOT NULL,
    amount_delta BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(user_id,event_key)
);
CREATE TABLE IF NOT EXISTS auth_sessions(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    family_id CHAR(36) NOT NULL,
    refresh_token_hash BINARY(32) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP,
    revoked_at TIMESTAMP,
    replaced_by_id BIGINT REFERENCES auth_sessions(id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS boards(
    id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(80) NOT NULL UNIQUE,
    type VARCHAR(16) NOT NULL, team_id BIGINT, name VARCHAR(100) NOT NULL,
    display_order SMALLINT NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS board_tags(
    id BIGINT AUTO_INCREMENT PRIMARY KEY, board_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL, tag_group VARCHAR(16) NOT NULL, driver_id BIGINT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE, display_order SMALLINT NOT NULL
);
