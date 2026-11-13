package com.boxbox.radio.post.dto;

import java.time.Instant;
import java.util.List;

public record PostSummaryResponse(String postId, String boardId, String title, String preview,
        PostDetailResponse.Author author, List<PostDetailResponse.Tag> tags, String thumbnailUrl,
        long likeCount, long commentCount, long bookmarkCount, long viewCount, Instant createdAt,
        Boolean likedByMe, Boolean bookmarkedByMe) { }
