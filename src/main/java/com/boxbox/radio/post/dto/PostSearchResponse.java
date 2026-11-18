package com.boxbox.radio.post.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

public record PostSearchResponse(List<Item> items, long totalCount, String nextCursor, boolean hasNext) {
    public record Item(@JsonUnwrapped PostSummaryResponse summary, String boardName, boolean isArchived) { }
}
