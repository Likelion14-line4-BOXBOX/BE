package com.boxbox.radio.board.reaction;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.reaction.dto.CommentLikeResponse;
import com.boxbox.radio.board.reaction.dto.PostBookmarkResponse;
import com.boxbox.radio.board.reaction.dto.PostLikeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 댓글의 post_id를 먼저 읽고 잠금을 기다려도 이후 집계가 이전 스냅샷에 머무르지 않게 한다.
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReactionService {
    private final ReactionRepository repository;

    public PostLikeResponse likePost(long postId, String loginId) {
        long count = changePost(postId, loginId, "LIKE", true);
        return new PostLikeResponse(Long.toString(postId), true, count);
    }

    public void unlikePost(long postId, String loginId) { changePost(postId, loginId, "LIKE", false); }

    public PostBookmarkResponse bookmarkPost(long postId, String loginId) {
        long count = changePost(postId, loginId, "BOOKMARK", true);
        return new PostBookmarkResponse(Long.toString(postId), true, count);
    }

    public void unbookmarkPost(long postId, String loginId) { changePost(postId, loginId, "BOOKMARK", false); }

    public CommentLikeResponse likeComment(long commentId, String loginId) {
        long count = changeComment(commentId, loginId, true);
        return new CommentLikeResponse(Long.toString(commentId), true, count);
    }

    public void unlikeComment(long commentId, String loginId) { changeComment(commentId, loginId, false); }

    private long changePost(long postId, String loginId, String type, boolean registered) {
        long userId = requireViewer(loginId);
        if (!repository.lockPost(postId)) throw notFound("POST");
        repository.setPostReaction(postId, userId, type, registered);
        return registered ? repository.postCount(postId, type) : 0;
    }

    private long changeComment(long commentId, String loginId, boolean registered) {
        long userId = requireViewer(loginId);
        long postId = repository.postId(commentId).orElseThrow(() -> notFound("COMMENT"));
        // 기존 게시글·댓글 변경과 같은 회원 → 게시글 → 댓글 순서로 직렬화한다.
        if (!repository.lockPost(postId) || !repository.lockComment(commentId, postId)) throw notFound("COMMENT");
        repository.setCommentLike(commentId, userId, registered);
        return registered ? repository.commentCount(commentId) : 0;
    }

    private long requireViewer(String loginId) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        var viewer = repository.lockViewer(loginId).orElseThrow(ApiException::unauthorized);
        if ("SUSPENDED".equals(viewer.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REACTION_RESTRICTED", "반응 등록 및 취소가 제한된 계정입니다.");
        }
        if (!"ACTIVE".equals(viewer.status())) throw ApiException.unauthorized();
        return viewer.id();
    }

    private static ApiException notFound(String target) {
        return new ApiException(HttpStatus.NOT_FOUND, target + "_NOT_FOUND",
                "COMMENT".equals(target) ? "댓글을 찾을 수 없습니다." : "게시글을 찾을 수 없습니다.");
    }
}
