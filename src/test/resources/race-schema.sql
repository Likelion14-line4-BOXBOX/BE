CREATE TABLE IF NOT EXISTS races (
    id BIGINT PRIMARY KEY,
    season_year SMALLINT NOT NULL,
    round_number SMALLINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    country_name VARCHAR(80) NOT NULL,
    circuit_name VARCHAR(120) NOT NULL,
    circuit_key VARCHAR(80) NOT NULL,
    external_key VARCHAR(80),
    scheduled_start_at TIMESTAMP,
    actual_end_at TIMESTAMP,
    status VARCHAR(24) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS race_sessions (
    id BIGINT PRIMARY KEY,
    race_id BIGINT NOT NULL REFERENCES races (id),
    type VARCHAR(24) NOT NULL,
    name VARCHAR(100) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS race_results (
    id BIGINT PRIMARY KEY,
    race_id BIGINT NOT NULL REFERENCES races (id),
    driver_id BIGINT NOT NULL,
    result_version INT NOT NULL,
    finish_rank SMALLINT,
    classification_status VARCHAR(24) NOT NULL,
    official_points DECIMAL(8, 2),
    result_status VARCHAR(24) NOT NULL,
    verified_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
