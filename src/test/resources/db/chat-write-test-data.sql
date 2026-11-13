-- 조회 커서 테스트의 의도적인 미커밋 순번 픽스처를 쓰기 테스트에서는 제거한다.
DELETE FROM stream_events WHERE room_id=1 AND stream_sequence=11;
INSERT INTO users(id,login_id,password_hash,nickname,status) VALUES (6,'newcomer','test-only-hash','새 참가자','ACTIVE');
