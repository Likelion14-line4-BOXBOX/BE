package com.boxbox.radio.post;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.post.dto.PostCreatedResponse;
import com.boxbox.radio.post.dto.PostDetailResponse;
import com.boxbox.radio.post.dto.PostUpdatedResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PostController {
    private final PostService service;
    private final BoardViewerResolver viewerResolver;

    @GetMapping("/api/posts/{postId}")
    public ApiResponse<PostDetailResponse> detail(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        long id = BoardFilter.parseId(postId, "postId");
        String session = loginId == null ? request.getSession(true).getId() : null;
        return ApiResponse.success(service.detail(id, loginId, session));
    }

    @PostMapping("/api/boards/{boardId}/posts")
    public ResponseEntity<ApiResponse<PostCreatedResponse>> create(@PathVariable String boardId,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                service.create(BoardFilter.parseId(boardId, "boardId"), loginId, body)));
    }

    @PatchMapping("/api/posts/{postId}")
    public ApiResponse<PostUpdatedResponse> update(@PathVariable String postId,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.update(BoardFilter.parseId(postId, "postId"), loginId, body));
    }

    @DeleteMapping("/api/posts/{postId}")
    public ResponseEntity<Void> delete(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        service.delete(BoardFilter.parseId(postId, "postId"), loginId);
        return ResponseEntity.noContent().build();
    }
}
