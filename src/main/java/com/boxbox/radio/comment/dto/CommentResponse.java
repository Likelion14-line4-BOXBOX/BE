package com.boxbox.radio.comment.dto;

import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import java.time.Instant;

public record CommentResponse(String commentId, String postId, String parentId, Author author, String content,
                              boolean isDeleted, long likeCount, Boolean likedByMe, long replyCount,
                              Instant createdAt, Instant updatedAt) { }
