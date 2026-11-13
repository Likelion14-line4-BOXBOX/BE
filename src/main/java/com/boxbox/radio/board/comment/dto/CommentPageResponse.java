package com.boxbox.radio.board.comment.dto;

import java.util.List;

public record CommentPageResponse(List<CommentResponse> items, String nextCursor, boolean hasNext) { }
