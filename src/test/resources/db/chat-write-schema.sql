-- H2 쓰기 검증 보강. 운영 MySQL은 전체 ERD를 그대로 사용한다.
ALTER TABLE chat_rooms ADD updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
-- H2는 UTF-16 단위로 VARCHAR 길이를 검사한다. 실제 500 code point 저장은 MySQL에서도 검증한다.
ALTER TABLE chat_messages ALTER COLUMN content VARCHAR(2000);
