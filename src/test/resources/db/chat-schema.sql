-- H2 채팅 조회 전용 보강. MySQL은 루트 전체 ERD 32개 테이블을 그대로 사용한다.
CREATE TABLE races (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, season_year SMALLINT NOT NULL, round_number SMALLINT NOT NULL,
    name VARCHAR(120) NOT NULL, country_name VARCHAR(80) NOT NULL, circuit_name VARCHAR(120) NOT NULL,
    circuit_key VARCHAR(80) NOT NULL, weekend_starts_at TIMESTAMP NOT NULL, weekend_ends_at TIMESTAMP NOT NULL,
    scheduled_start_at TIMESTAMP, actual_end_at TIMESTAMP, status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
    UNIQUE(season_year,round_number)
);
CREATE TABLE race_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, race_id BIGINT NOT NULL REFERENCES races(id), type VARCHAR(24) NOT NULL,
    session_number TINYINT NOT NULL DEFAULT 1, name VARCHAR(100) NOT NULL, starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP, status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED', actual_starts_at TIMESTAMP, actual_ends_at TIMESTAMP,
    UNIQUE(race_id,type,session_number)
);
CREATE TABLE chat_rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, race_id BIGINT NOT NULL UNIQUE REFERENCES races(id),
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', opens_at TIMESTAMP NOT NULL, closes_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP, stream_sequence BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE chat_participants (
    room_id BIGINT NOT NULL REFERENCES chat_rooms(id), user_id BIGINT NOT NULL REFERENCES users(id),
    first_joined_at TIMESTAMP NOT NULL, last_joined_at TIMESTAMP NOT NULL, first_message_at TIMESTAMP,
    stamp_recorded_at TIMESTAMP, stamp_trigger_type VARCHAR(16), PRIMARY KEY(room_id,user_id)
);
CREATE TABLE chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, room_id BIGINT NOT NULL, sender_id BIGINT NOT NULL,
    client_message_id CHAR(36) NOT NULL, request_hash BINARY(32) NOT NULL, content VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted_at TIMESTAMP,
    UNIQUE(room_id,sender_id,client_message_id), FOREIGN KEY(room_id,sender_id) REFERENCES chat_participants(room_id,user_id)
);
CREATE TABLE stream_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, room_id BIGINT REFERENCES chat_rooms(id), round_id BIGINT,
    stream_sequence BIGINT NOT NULL, event_type VARCHAR(40) NOT NULL, payload CLOB NOT NULL,
    occurred_at TIMESTAMP NOT NULL, published_at TIMESTAMP, expires_at TIMESTAMP NOT NULL,
    UNIQUE(room_id,stream_sequence), UNIQUE(round_id,stream_sequence)
);
