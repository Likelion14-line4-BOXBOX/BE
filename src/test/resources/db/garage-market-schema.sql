-- 개러지/마켓 실제 읽기 통합용 H2 스키마. MySQL은 deploy/db/schema.sql을 사용한다.
CREATE TABLE market_rounds (
    id BIGINT PRIMARY KEY, race_id BIGINT NOT NULL REFERENCES races(id), season_year INT NOT NULL,
    status VARCHAR(24) NOT NULL, open_at TIMESTAMP, close_at TIMESTAMP, publish_at TIMESTAMP,
    ranking_published_at TIMESTAMP, rule_version VARCHAR(40), rule_snapshot VARCHAR(1000),
    status_version BIGINT DEFAULT 1, settlement_status VARCHAR(16) DEFAULT 'NOT_STARTED'
);
CREATE TABLE market_stocks (
    id BIGINT PRIMARY KEY, round_id BIGINT REFERENCES market_rounds(id), season_year INT,
    driver_id BIGINT REFERENCES drivers(id), team_id BIGINT REFERENCES teams(id), base_rank INT,
    starting_price BIGINT, current_price BIGINT, theoretical_price DECIMAL(28,8)
);
CREATE TABLE positions (
    user_id BIGINT REFERENCES users(id), stock_id BIGINT REFERENCES market_stocks(id),
    quantity DECIMAL(20,0), total_cost BIGINT, PRIMARY KEY(user_id,stock_id)
);
CREATE TABLE user_season_baselines (
    user_id BIGINT REFERENCES users(id), season_year INT, starting_assets BIGINT, joined_at TIMESTAMP,
    PRIMARY KEY(user_id,season_year)
);
CREATE TABLE ranking_snapshots (
    id BIGINT PRIMARY KEY, season_year INT, round_id BIGINT REFERENCES market_rounds(id),
    scope VARCHAR(16), type VARCHAR(24), status VARCHAR(16), snapshot_at TIMESTAMP, published_at TIMESTAMP
);
CREATE TABLE ranking_entries (
    snapshot_id BIGINT REFERENCES ranking_snapshots(id), user_id BIGINT REFERENCES users(id),
    `rank` BIGINT, total_assets BIGINT, PRIMARY KEY(snapshot_id,user_id)
);
