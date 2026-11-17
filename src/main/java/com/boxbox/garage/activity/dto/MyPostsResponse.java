package com.boxbox.garage.activity.dto;

import com.boxbox.radio.post.dto.PostSummaryResponse;
import java.util.List;

public record MyPostsResponse(List<PostSummaryResponse> items, String nextCursor, boolean hasNext) { }
