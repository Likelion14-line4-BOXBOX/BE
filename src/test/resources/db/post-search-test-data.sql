-- post-test-data.sql 이후 실행하는 격리 테스트 추가 데이터다.
INSERT INTO boards(id,code,type,team_id,name,display_order,is_active) VALUES
    (30,'BEGINNER_ACTIVE','BEGINNER',NULL,'입문자 게시판',3,TRUE),
    (40,'FREE_EMPTY','FREE',NULL,'빈 게시판',4,TRUE);
INSERT INTO board_tags(id,board_id,name,tag_group,is_active,display_order) VALUES
    (102,10,'검색태그','TOPIC',TRUE,3);
INSERT INTO posts(id,board_id,author_id,tag_id,title,content,view_count,created_at,updated_at) VALUES
    (5,10,1,100,'레이스 분석','전략 이야기',10,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (6,10,2,NULL,'레이스 소식','분석 본문',20,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (7,10,1,101,'보관 글','원문 그대로 보존',30,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (8,20,1,200,'자유 이야기','좋아요 동률',40,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (9,20,2,NULL,'자유 소식','좋아요 동률',50,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (10,30,1,NULL,'입문 질문','입문 본문',0,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (11,10,1,102,'태그만 검색','일반 본문',0,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (12,10,1,NULL,'100%_!','문자 그대로 검색',0,'2026-11-01 00:00:00','2026-11-01 00:00:00'),
    (13,10,1,NULL,'오래된 글','오래된 본문',0,'2026-11-01 00:00:00','2026-11-01 00:00:00');
INSERT INTO post_reactions(post_id,user_id,type) VALUES
    (5,1,'LIKE'),(5,2,'LIKE'),(5,3,'LIKE'),(6,1,'LIKE'),(6,2,'LIKE'),
    (8,1,'LIKE'),(8,2,'LIKE'),(8,3,'LIKE'),(9,1,'LIKE'),(9,2,'LIKE'),(9,3,'LIKE');
INSERT INTO comments(id,post_id,author_id,content,created_at,updated_at,deleted_at) VALUES
    (4,6,1,'댓글1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
    (5,6,2,'댓글2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
    (6,6,1,'댓글3',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
    (7,6,1,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (8,5,1,'댓글',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL);
