package com.boxbox.radio.board.reaction;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.board.reaction.dto.CommentLikeResponse;
import com.boxbox.radio.board.reaction.dto.PostBookmarkResponse;
import com.boxbox.radio.board.reaction.dto.PostLikeResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ReactionController {
    private final ReactionService service;
    private final BoardViewerResolver viewerResolver;

    @PutMapping("/api/posts/{postId}/like")
    ApiResponse<PostLikeResponse> likePost(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.likePost(BoardFilter.parseId(postId, "postId"), loginId));
    }

    @DeleteMapping("/api/posts/{postId}/like")
    ResponseEntity<Void> unlikePost(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        service.unlikePost(BoardFilter.parseId(postId, "postId"), loginId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/posts/{postId}/bookmark")
    ApiResponse<PostBookmarkResponse> bookmarkPost(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.bookmarkPost(BoardFilter.parseId(postId, "postId"), loginId));
    }

    @DeleteMapping("/api/posts/{postId}/bookmark")
    ResponseEntity<Void> unbookmarkPost(@PathVariable String postId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        service.unbookmarkPost(BoardFilter.parseId(postId, "postId"), loginId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/comments/{commentId}/like")
    ApiResponse<CommentLikeResponse> likeComment(@PathVariable String commentId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(service.likeComment(BoardFilter.parseId(commentId, "commentId"), loginId));
    }

    @DeleteMapping("/api/comments/{commentId}/like")
    ResponseEntity<Void> unlikeComment(@PathVariable String commentId, HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        service.unlikeComment(BoardFilter.parseId(commentId, "commentId"), loginId);
        return ResponseEntity.noContent().build();
    }
}
