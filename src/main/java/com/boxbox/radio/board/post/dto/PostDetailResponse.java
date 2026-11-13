package com.boxbox.radio.board.post.dto;

import java.time.Instant;
import java.util.List;

public record PostDetailResponse(
        String postId, String boardId, String title, String preview, Author author,
        List<Tag> tags, String thumbnailUrl, long likeCount, long commentCount,
        long bookmarkCount, long viewCount, Instant createdAt, Boolean likedByMe,
        Boolean bookmarkedByMe, String content, List<Media> media, Instant updatedAt,
        boolean canEdit, boolean canDelete) {
    public record Author(String userId, String nickname, String avatarAssetId) { }
    public record Tag(String tagId, String name) { }
    public record Media(String mediaId, String url, int width, int height) { }
}
