UPDATE comments SET created_at='2026-11-01 01:00:00',updated_at='2026-11-01 01:00:00' WHERE id IN (1,2);
UPDATE comments SET created_at='2026-11-01 01:01:00',updated_at='2026-11-01 01:01:00' WHERE id=3;
INSERT INTO comments(id,post_id,author_id,parent_id,content,created_at,updated_at,deleted_at) VALUES
    (4,1,2,1,'첫 답글','2026-11-01 01:02:00','2026-11-01 01:02:00',NULL),
    (5,1,1,1,NULL,'2026-11-01 01:03:00','2026-11-01 01:03:00','2026-11-02 00:00:00'),
    (6,1,1,NULL,'마지막 댓글','2026-11-01 01:04:00','2026-11-01 01:04:00',NULL),
    (7,1,1,2,'두 번째 답글','2026-11-01 01:05:00','2026-11-01 01:05:00',NULL),
    (8,2,2,NULL,'다른 글 댓글','2026-11-01 01:00:00','2026-11-01 01:00:00',NULL),
    (9,3,1,NULL,'삭제 글 댓글','2026-11-01 01:00:00','2026-11-01 01:00:00',NULL),
    (10,4,1,NULL,'비활성 글 댓글','2026-11-01 01:00:00','2026-11-01 01:00:00',NULL);
INSERT INTO comment_likes(comment_id,user_id) VALUES (1,1),(1,2),(3,2),(4,1);
