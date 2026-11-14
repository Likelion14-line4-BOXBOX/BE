INSERT INTO races(id,season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at) VALUES
 (1,2026,1,'현재 GP','Korea','Circuit 1','c1','2026-11-01 00:00:00','2026-11-30 00:00:00'),
 (2,2026,2,'지난 GP','Korea','Circuit 2','c2','2026-11-01 00:00:00','2026-11-08 00:00:00');
INSERT INTO chat_rooms(id,race_id,status,opens_at,closes_at,stream_sequence) VALUES
 (1,1,'LIVE','2026-11-01 00:00:00','2026-11-30 00:00:00',1),
 (2,2,'ARCHIVED','2026-11-01 00:00:00','2026-11-08 00:00:00',0);
INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at) VALUES
 (1,1,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
 (2,2,'2026-11-01 00:00:00','2026-11-01 00:00:00');
INSERT INTO chat_messages(id,room_id,sender_id,client_message_id,request_hash,content,created_at,deleted_at) VALUES
 (1,1,1,'00000000-0000-0000-0000-000000000001',X'0000000000000000000000000000000000000000000000000000000000000000','살아 있는 메시지','2026-11-01 10:00:00',NULL),
 (2,1,1,'00000000-0000-0000-0000-000000000002',X'0000000000000000000000000000000000000000000000000000000000000000',NULL,'2026-11-01 10:01:00','2026-11-02 00:00:00'),
 (3,2,2,'00000000-0000-0000-0000-000000000003',X'0000000000000000000000000000000000000000000000000000000000000000','지난 방 메시지','2026-11-01 10:00:00',NULL);
INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at) VALUES
 (1,1,'MESSAGE_CREATED','{"roomId":"1","messageId":"1","clientMessageId":"00000000-0000-0000-0000-000000000001"}','2026-11-01 10:00:00','2026-11-01 10:10:00');
