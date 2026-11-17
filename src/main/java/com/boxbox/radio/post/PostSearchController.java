package com.boxbox.radio.post;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.post.dto.BoardPostsResponse;
import com.boxbox.radio.post.dto.PostSearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class PostSearchController {
    private final PostSearchService service;
    private final BoardViewerResolver viewerResolver;

    @GetMapping("/api/boards/{boardId}/posts")
    ApiResponse<BoardPostsResponse> boardPosts(@PathVariable String boardId,
            @RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.boardPosts(PostSearchFilter.from(BoardFilter.parseId(boardId, "boardId"), params), loginId));
    }

    @GetMapping("/api/posts")
    ApiResponse<PostSearchResponse> search(@RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.search(PostSearchFilter.from(null, params), loginId));
    }
}
