UPDATE users SET show_market_assets=FALSE WHERE id=1;
UPDATE users SET show_market_assets=TRUE WHERE id=2;
UPDATE drivers SET car_number=NULL WHERE id=11;
INSERT INTO races(id,season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at) VALUES
 (1,2026,1,'참여한 GP','Korea','Circuit 1','c1','2026-11-01 00:00:00','2026-11-30 00:00:00'),
 (2,2026,2,'도장 없는 GP','Korea','Circuit 2','c2','2026-11-01 00:00:00','2026-11-30 00:00:00'),
 (3,2026,3,'방 없는 GP','Korea','Circuit 3','c3','2026-11-01 00:00:00','2026-11-30 00:00:00'),
 (4,2025,1,'이전 시즌 GP','Korea','Circuit 4','c4','2025-11-01 00:00:00','2025-11-30 00:00:00');
INSERT INTO chat_rooms(id,race_id,status,opens_at,closes_at,stream_sequence) VALUES
 (1,1,'ARCHIVED','2026-11-01 00:00:00','2026-11-02 00:00:00',0),
 (2,2,'ARCHIVED','2026-11-01 00:00:00','2026-11-02 00:00:00',0),
 (4,4,'ARCHIVED','2025-11-01 00:00:00','2025-11-02 00:00:00',0);
INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,first_message_at,stamp_recorded_at,stamp_trigger_type) VALUES
 (1,1,'2026-11-01 00:00:00','2026-11-01 00:00:00',NULL,'2026-11-01 00:00:00','JOIN'),
 (2,1,'2026-11-01 00:00:00','2026-11-01 00:00:00','2026-11-01 00:00:00',NULL,NULL),
 (4,1,'2025-11-01 00:00:00','2025-11-01 00:00:00',NULL,'2025-11-01 00:00:00','JOIN');
