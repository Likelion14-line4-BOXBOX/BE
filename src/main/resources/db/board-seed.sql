-- 직접 입력된 teams / drivers 기준정보 다음에 수동 실행한다.
-- 존재하는 항목은 덮어쓰지 않는다. 팀/선수나 외부 API 결과를 임의로 생성하지 않는다.
INSERT INTO boards (code, type, team_id, name, display_order)
SELECT CONCAT('TEAM_', t.code), 'TEAM', t.id, CONCAT(t.name, ' 게시판'), t.display_order
FROM teams t
WHERE NOT EXISTS (SELECT 1 FROM boards b WHERE b.team_id = t.id);

INSERT INTO boards (code, type, name, display_order)
SELECT 'FREE', 'FREE', '자유 게시판', 1000
WHERE NOT EXISTS (SELECT 1 FROM boards WHERE code = 'FREE');

INSERT INTO boards (code, type, name, display_order)
SELECT 'BEGINNER', 'BEGINNER', '입문자 게시판', 1001
WHERE NOT EXISTS (SELECT 1 FROM boards WHERE code = 'BEGINNER');

INSERT INTO board_tags (board_id, name, tag_group, display_order)
SELECT b.id, t.name, 'TEAM', 1
FROM boards b JOIN teams t ON t.id = b.team_id
WHERE b.type = 'TEAM'
  AND NOT EXISTS (SELECT 1 FROM board_tags bt WHERE bt.board_id = b.id AND bt.name = t.name);

INSERT INTO board_tags (board_id, name, tag_group, driver_id, display_order)
SELECT b.id, d.name, 'DRIVER', d.id, 10 + ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY d.id)
FROM boards b JOIN drivers d ON d.team_id = b.team_id
WHERE b.type = 'TEAM'
  AND NOT EXISTS (SELECT 1 FROM board_tags bt WHERE bt.board_id = b.id AND bt.name = d.name);

-- 화면명세의 자유/입문자 태그 예시를 초기값으로 사용하며 운영자가 DB에서 관리한다.
INSERT INTO board_tags (board_id, name, tag_group, display_order)
SELECT b.id, topics.name, 'TOPIC', topics.display_order
FROM boards b
JOIN (
    SELECT 'FREE' AS code, '굿즈' AS name, 1 AS display_order
    UNION ALL SELECT 'FREE', '레이스', 2
    UNION ALL SELECT 'FREE', '친목', 3
    UNION ALL SELECT 'FREE', '질문', 4
    UNION ALL SELECT 'FREE', '공구', 5
    UNION ALL SELECT 'BEGINNER', '입문자', 1
    UNION ALL SELECT 'BEGINNER', '고인물', 2
    UNION ALL SELECT 'BEGINNER', '친목', 3
    UNION ALL SELECT 'BEGINNER', '질문', 4
    UNION ALL SELECT 'BEGINNER', '정보', 5
) topics ON topics.code = b.code
WHERE NOT EXISTS (
    SELECT 1 FROM board_tags bt WHERE bt.board_id = b.id AND bt.name = topics.name
);
