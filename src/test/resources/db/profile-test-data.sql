DELETE FROM posts;
DELETE FROM board_tags;
DELETE FROM boards;
DELETE FROM users;
DELETE FROM drivers;
DELETE FROM teams;

INSERT INTO teams (id, code, name, display_order, championship_rank, stats_season_year, stats_as_of) VALUES
    (1, 'TEAM_A', '팀 A', 1, 3, 2026, '2026-11-05 00:00:00'),
    (2, 'TEAM_B', '팀 B', 2, NULL, NULL, NULL);
INSERT INTO drivers (id, code, name, team_id, car_number, championship_rank, stats_season_year, stats_as_of) VALUES
    (11, 'DRIVER_A', '선수 A', 1, 1, 7, 2026, '2026-11-05 00:00:00'),
    (12, 'DRIVER_B', '선수 B', 2, 2, NULL, NULL, NULL),
    (13, 'DRIVER_C', '무소속 선수', NULL, NULL, NULL, NULL, NULL);
INSERT INTO users (id, login_id, password_hash, nickname, bio, favorite_team_id, favorite_driver_id,
                   status, created_at, updated_at, onboarding_completed, coin_balance, wallet_version) VALUES
    (1, 'viewer', 'test-only-hash', '기존닉네임', '기존소개', 1, 11, 'ACTIVE',
     '2026-11-05 00:00:00', '2026-11-05 00:00:00', TRUE, 12345, 7),
    (2, 'other', 'test-only-hash', 'TakenName', '', NULL, NULL, 'ACTIVE',
     '2026-11-05 00:00:00', '2026-11-05 00:00:00', FALSE, 0, 0),
    (3, 'suspended', 'test-only-hash', '정지회원', '', NULL, NULL, 'SUSPENDED',
     '2026-11-05 00:00:00', '2026-11-05 00:00:00', FALSE, 0, 0),
    (4, 'withdrawn', 'test-only-hash', '탈퇴회원', '', NULL, NULL, 'WITHDRAWN',
     '2026-11-05 00:00:00', '2026-11-05 00:00:00', FALSE, 0, 0);
INSERT INTO users (id, login_id, password_hash, nickname, status, deleted_at) VALUES
    (5, 'deleted', 'test-only-hash', '삭제회원', 'ACTIVE', CURRENT_TIMESTAMP);
