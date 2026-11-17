package com.boxbox.radio.post.dto;

import java.util.List;

public record BoardPostsResponse(List<PostSummaryResponse> items, String nextCursor, boolean hasNext,
                                 long totalCount, PostSummaryResponse popularPost) { }
