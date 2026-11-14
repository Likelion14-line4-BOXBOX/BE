DELETE FROM stream_events;
DELETE FROM chat_messages;
DELETE FROM chat_participants;
INSERT INTO races(id,season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at) VALUES
 (8,2026,7,'채팅방 없는 GP','Korea','Circuit 8','c8','2026-11-01 00:00:00','2026-11-30 00:00:00');
INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,first_message_at,stamp_recorded_at,stamp_trigger_type) VALUES
 (1,1,'2026-11-01 00:00:00','2026-11-02 00:00:00',NULL,'2026-11-01 00:00:00','JOIN'),
 (3,1,'2026-11-01 00:00:00','2026-11-02 00:00:00','2026-11-01 00:00:00',NULL,NULL),
 (5,1,'2025-11-01 00:00:00','2025-11-01 00:00:00','2025-11-01 00:00:00','2025-11-01 00:00:00','MESSAGE');
