package com.boxbox.home;

import com.boxbox.radio.post.dto.PostDetailResponse;
import com.boxbox.radio.post.dto.PostSummaryResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

record HomeResponse(Race nextRace, Session nextSession, ChatRoom liveChatRoom, MarketRound marketRound,
                    List<FeaturedPost> featuredPosts, MarketSummary myMarketSummary) {
    record Race(String raceId, int season, int roundNumber, String name, String countryName, String circuitName,
                String circuitKey, Instant scheduledStartAt, Instant actualEndAt, String status,
                Instant weekendStartsAt, Instant weekendEndsAt) {}

    record Session(String sessionId, String name, Instant startsAt) {}

    record ChatRoom(String roomId, String raceId, String raceName, String status, long onlineCount,
                    Instant opensAt, Instant closesAt) {}

    record MarketRound(String roundId, String raceId, int season, int roundNumber, String raceName, String status,
                       Instant openAt, Instant closeAt, Instant publishAt, Instant rankingPublishedAt,
                       String ruleVersion, BigInteger statusVersion) {}

    record FeaturedPost(String postId, String boardId, String boardName, String boardType, String title, String preview,
                        PostDetailResponse.Author author, List<PostDetailResponse.Tag> tags, String thumbnailUrl,
                        long likeCount, long commentCount, long bookmarkCount, long viewCount, Instant createdAt,
                        Boolean likedByMe, Boolean bookmarkedByMe) {
        static FeaturedPost from(PostSummaryResponse post, String boardName, String boardType) {
            return new FeaturedPost(post.postId(), post.boardId(), boardName, boardType, post.title(), post.preview(),
                    post.author(), post.tags(), post.thumbnailUrl(), post.likeCount(), post.commentCount(),
                    post.bookmarkCount(), post.viewCount(), post.createdAt(), post.likedByMe(), post.bookmarkedByMe());
        }
    }

    record MarketSummary(BigInteger totalAssets, BigDecimal returnRate, Long rank, Instant snapshotAt) {}
}
