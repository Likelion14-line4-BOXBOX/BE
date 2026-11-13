-- 게시글 통합 테스트 전용 DB. 운영 스키마는 루트 docs/erd/schema.mysql.all.sql을 적용한다.
CREATE TABLE teams (id BIGINT PRIMARY KEY, code VARCHAR(40) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, display_order SMALLINT NOT NULL);
CREATE TABLE drivers (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, team_id BIGINT REFERENCES teams(id));
CREATE TABLE users (
    id BIGINT PRIMARY KEY, login_id VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL, nickname VARCHAR(20) NOT NULL UNIQUE,
    avatar_asset_id VARCHAR(80) NOT NULL DEFAULT 'avatar_default',
    favorite_team_id BIGINT REFERENCES teams(id), status VARCHAR(16) NOT NULL,
    deleted_at TIMESTAMP
);
CREATE TABLE boards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(80) NOT NULL UNIQUE,
    type VARCHAR(16) NOT NULL, team_id BIGINT UNIQUE REFERENCES teams(id),
    name VARCHAR(100) NOT NULL, display_order SMALLINT NOT NULL, is_active BOOLEAN NOT NULL
);
CREATE TABLE board_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, board_id BIGINT NOT NULL REFERENCES boards(id),
    name VARCHAR(80) NOT NULL, tag_group VARCHAR(16) NOT NULL,
    driver_id BIGINT REFERENCES drivers(id), is_active BOOLEAN NOT NULL, display_order SMALLINT NOT NULL,
    UNIQUE (board_id, id), UNIQUE (board_id, name)
);
CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, board_id BIGINT NOT NULL REFERENCES boards(id),
    author_id BIGINT NOT NULL REFERENCES users(id), tag_id BIGINT,
    -- H2는 보충 문자 길이를 UTF-16으로 계산한다. 실제 VARCHAR(20) 경계는 MySQL 테스트로 확인한다.
    title VARCHAR(80) NOT NULL, content LONGTEXT, view_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP,
    FOREIGN KEY (board_id, tag_id) REFERENCES board_tags(board_id, id),
    CHECK (deleted_at IS NOT NULL OR (content IS NOT NULL AND CHAR_LENGTH(TRIM(content)) > 0))
);
CREATE TABLE media_files (
    id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES users(id),
    object_key VARCHAR(255) NOT NULL UNIQUE, purpose VARCHAR(24) NOT NULL DEFAULT 'POST_IMAGE',
    content_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL, width INT NOT NULL, height INT NOT NULL,
    status VARCHAR(16) NOT NULL, expires_at TIMESTAMP, created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP,
    post_id BIGINT REFERENCES posts(id), position SMALLINT,
    UNIQUE (post_id, position),
    CHECK ((post_id IS NULL AND position IS NULL) OR (post_id IS NOT NULL AND position BETWEEN 1 AND 5)),
    CHECK (status <> 'ATTACHED' OR post_id IS NOT NULL)
);
CREATE TABLE comments (
    id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL REFERENCES posts(id),
    author_id BIGINT NOT NULL REFERENCES users(id), parent_id BIGINT,
    content VARCHAR(1000), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP
);
CREATE TABLE post_reactions (
    post_id BIGINT REFERENCES posts(id), user_id BIGINT REFERENCES users(id),
    type VARCHAR(16), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id, type)
);
CREATE TABLE post_view_dedup (
    post_id BIGINT REFERENCES posts(id), visitor_hash BINARY(32), last_counted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (post_id, visitor_hash)
);
