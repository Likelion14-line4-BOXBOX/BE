DELETE FROM post_view_dedup;
DELETE FROM post_reactions;
DELETE FROM comments;
DELETE FROM media_files;
DELETE FROM posts;
DELETE FROM board_tags;
DELETE FROM boards;
DELETE FROM users;
DELETE FROM drivers;
DELETE FROM teams;

INSERT INTO teams (id,code,name,display_order) VALUES (1,'TEAM_A','팀 A',1);
INSERT INTO users (id,login_id,password_hash,nickname,status,deleted_at) VALUES
    (1,'viewer','test-only-hash','작성자','ACTIVE',NULL),
    (2,'other','test-only-hash','다른 회원','ACTIVE',NULL),
    (3,'suspended','test-only-hash','정지 회원','SUSPENDED',NULL),
    (4,'withdrawn','test-only-hash','탈퇴 회원','WITHDRAWN',NULL),
    (5,'deleted','test-only-hash','삭제 회원','ACTIVE','2026-11-01 00:00:00');
INSERT INTO boards (id,code,type,team_id,name,display_order,is_active) VALUES
    (10,'TEAM_A','TEAM',1,'팀 게시판',1,TRUE),
    (20,'FREE','FREE',NULL,'자유 게시판',2,TRUE),
    (50,'BEGINNER','BEGINNER',NULL,'비활성 게시판',3,FALSE);
INSERT INTO board_tags (id,board_id,name,tag_group,is_active,display_order) VALUES
    (100,10,'질문','TOPIC',TRUE,1), (101,10,'과거 태그','TOPIC',FALSE,2),
    (200,20,'자유','TOPIC',TRUE,1);
INSERT INTO posts (id,board_id,author_id,tag_id,title,content,view_count,created_at,updated_at,deleted_at) VALUES
    (1,10,1,100,'기존 글','기존 본문',7,'2026-11-01 03:04:05','2026-11-01 03:04:05',NULL),
    (2,10,2,NULL,'다른 글','다른 본문',0,'2026-11-01 00:00:00','2026-11-01 00:00:00',NULL),
    (3,10,1,NULL,'삭제 글',NULL,0,'2026-11-01 00:00:00','2026-11-01 00:00:00','2026-11-02 00:00:00'),
    (4,50,1,NULL,'비활성 글','본문',0,'2026-11-01 00:00:00','2026-11-01 00:00:00',NULL);
INSERT INTO media_files (id,owner_id,object_key,purpose,content_type,size_bytes,width,height,status,expires_at,post_id,position,deleted_at,created_at,updated_at) VALUES
    (10,1,'posts/가 나.png','POST_IMAGE','image/png',100,1200,800,'ATTACHED',NULL,1,1,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (11,1,'posts/b.png','POST_IMAGE','image/png',100,600,400,'ATTACHED',NULL,1,2,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (12,1,'posts/c.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (13,2,'posts/d.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (14,1,'posts/e.png','POST_IMAGE','image/png',100,600,400,'UPLOADED','2020-01-01 00:00:00',NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (15,1,'posts/f.png','POST_IMAGE','image/png',100,600,400,'DELETED',NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (16,1,'posts/g.png','POST_IMAGE','image/png',100,600,400,'ATTACHED',NULL,2,1,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (17,1,'posts/h.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (18,1,'posts/i.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (19,1,'posts/j.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    (20,1,'posts/k.png','POST_IMAGE','image/png',100,600,400,'UPLOADED',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO comments (id,post_id,author_id,content,created_at,updated_at,deleted_at) VALUES
    (1,1,1,'댓글',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
    (2,1,2,'댓글2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
    (3,1,1,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO post_reactions (post_id,user_id,type) VALUES (1,1,'LIKE'),(1,2,'LIKE'),(1,2,'BOOKMARK');
