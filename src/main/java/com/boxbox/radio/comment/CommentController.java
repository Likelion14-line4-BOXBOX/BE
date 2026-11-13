package com.boxbox.radio.comment;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.comment.dto.CommentPageResponse;
import com.boxbox.radio.comment.dto.CommentResponse;
import com.boxbox.radio.comment.dto.CommentUpdatedResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class CommentController {
    private final CommentService service;
    private final BoardViewerResolver viewerResolver;

    @GetMapping("/api/posts/{postId}/comments")
    ApiResponse<CommentPageResponse> list(@PathVariable String postId,@RequestParam Map<String,String> params,
                                         HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.list(BoardFilter.parseId(postId,"postId"),params,loginId));
    }

    @PostMapping("/api/posts/{postId}/comments")
    ResponseEntity<ApiResponse<CommentResponse>> create(@PathVariable String postId,@RequestBody Map<String,Object> body,
                                                        HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                service.create(BoardFilter.parseId(postId,"postId"),loginId,body)));
    }

    @PatchMapping("/api/comments/{commentId}")
    ApiResponse<CommentUpdatedResponse> update(@PathVariable String commentId,@RequestBody Map<String,Object> body,
                                               HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.update(BoardFilter.parseId(commentId,"commentId"),loginId,body));
    }

    @DeleteMapping("/api/comments/{commentId}")
    ResponseEntity<Void> delete(@PathVariable String commentId,HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        service.delete(BoardFilter.parseId(commentId,"commentId"),loginId);
        return ResponseEntity.noContent().build();
    }
}
