DELETE FROM posts;
DELETE FROM board_tags;
DELETE FROM boards;
DELETE FROM users;
DELETE FROM drivers;
DELETE FROM teams;

INSERT INTO teams (id, code, name, display_order) VALUES
    (1, 'TEAM_A', '팀 A', 2), (2, 'TEAM_B', '팀 B', 1), (3, 'TEAM_C', '팀 C', 3);
INSERT INTO drivers (id, name, team_id) VALUES (11, '선수 A', 1), (12, '선수 B', 1);
INSERT INTO users (id, login_id, favorite_team_id, status, deleted_at) VALUES
    (1, 'viewer', 1, 'ACTIVE', NULL),
    (2, 'no_favorite', NULL, 'ACTIVE', NULL),
    (3, 'suspended', 1, 'SUSPENDED', NULL),
    (4, 'deleted', 1, 'ACTIVE', CURRENT_TIMESTAMP),
    (5, 'withdrawn', 1, 'WITHDRAWN', NULL);
INSERT INTO boards (id, code, type, team_id, name, display_order, is_active) VALUES
    (10, 'TEAM_A', 'TEAM', 1, '팀 A 게시판', 2, TRUE),
    (20, 'TEAM_B', 'TEAM', 2, '팀 B 게시판', 1, TRUE),
    (30, 'FREE', 'FREE', NULL, '자유 게시판', 1000, TRUE),
    (40, 'BEGINNER', 'BEGINNER', NULL, '입문자 게시판', 1001, TRUE),
    (50, 'TEAM_C', 'TEAM', 3, '비활성 게시판', 3, FALSE);
INSERT INTO board_tags (id, board_id, name, tag_group, driver_id, is_active, display_order) VALUES
    (100, 10, '팀 A', 'TEAM', NULL, TRUE, 1),
    (101, 10, '선수 A', 'DRIVER', 11, TRUE, 2),
    (102, 10, '과거 태그', 'DRIVER', 12, FALSE, 3),
    (103, 10, '정보', 'TOPIC', NULL, TRUE, 2),
    (200, 20, '질문', 'TOPIC', NULL, TRUE, 1);
INSERT INTO posts (id, board_id, deleted_at) VALUES
    (1, 10, NULL), (2, 10, NULL), (3, 10, CURRENT_TIMESTAMP), (4, 30, NULL);
