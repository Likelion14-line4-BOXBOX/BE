DELETE FROM comment_likes;
DELETE FROM comments WHERE parent_id IS NOT NULL;
DELETE FROM comments;
