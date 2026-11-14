-- H2 전용. 운영 MySQL은 루트 전체 ERD의 reports 테이블을 그대로 사용한다.
CREATE TABLE reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id BIGINT NOT NULL REFERENCES users(id), target_type VARCHAR(24) NOT NULL,
    post_id BIGINT REFERENCES posts(id), comment_id BIGINT REFERENCES comments(id), message_id BIGINT REFERENCES chat_messages(id),
    reason VARCHAR(24) NOT NULL,
    -- H2는 UTF-16 단위 길이 계산을 한다. 1000 code point 저장은 MySQL에서도 검증한다.
    description VARCHAR(2000), status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    UNIQUE(reporter_id,post_id,reason), UNIQUE(reporter_id,comment_id,reason), UNIQUE(reporter_id,message_id,reason)
);
