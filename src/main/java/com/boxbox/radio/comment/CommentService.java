package com.boxbox.radio.comment;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.comment.dto.CommentPageResponse;
import com.boxbox.radio.comment.dto.CommentResponse;
import com.boxbox.radio.comment.dto.CommentUpdatedResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommentService {
    private final CommentRepository repository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPageResponse list(long postId, Map<String,String> params, String loginId) {
        Long viewer = loginId == null ? null : requireViewer(loginId,false).id();
        CommentPageRequest request = CommentPageRequest.from(params);
        if (!repository.readablePost(postId,false)) throw CommentRepository.postNotFound();
        Instant now = now();
        var cursor = request.cursor() == null ? null : CommentCursor.decode(request.cursor(),postId,request.size(),now);
        long maxId = cursor == null ? repository.maxId(postId) : cursor.maxId();
        Instant anchor = cursor == null ? now : cursor.anchor();
        var fetched = repository.page(postId,request.size(),maxId,anchor,cursor,viewer);
        boolean hasNext = fetched.size() > request.size();
        List<CommentResponse> items = List.copyOf(fetched.subList(0,Math.min(fetched.size(),request.size())));
        String next = null;
        if (hasNext) {
            var last = items.getLast();
            next = new CommentCursor(maxId,anchor,last.createdAt(),Long.parseLong(last.commentId())).encode(postId,request.size());
        }
        return new CommentPageResponse(items,next,hasNext);
    }

    @Transactional
    public CommentResponse create(long postId,String loginId,Map<String,Object> body) {
        var viewer = requireViewer(loginId,true);
        CommentInput input = CommentInput.from(body,true);
        if (!repository.readablePost(postId,true)) throw CommentRepository.postNotFound();
        if (input.parentId() != null) {
            var parent = repository.lockComment(input.parentId(),postId);
            if (parent.deletedAt() != null) throw CommentRepository.notFound();
            if (parent.parentId() != null) throw ApiException.invalidRequest("최상위 댓글에만 답글을 작성할 수 있습니다.");
        }
        long id = repository.insert(postId,viewer.id(),input,now());
        return repository.summary(id,viewer.id());
    }

    @Transactional
    public CommentUpdatedResponse update(long commentId,String loginId,Map<String,Object> body) {
        var viewer = requireViewer(loginId,true);
        requireOwnComment(commentId,viewer.id());
        CommentInput input = CommentInput.from(body,false);
        Instant now = now();
        repository.update(commentId,input.content(),now);
        return new CommentUpdatedResponse(Long.toString(commentId),input.content(),now);
    }

    @Transactional
    public void delete(long commentId,String loginId) {
        var viewer = requireViewer(loginId,true);
        requireOwnComment(commentId,viewer.id());
        repository.delete(commentId,now());
    }

    private void requireOwnComment(long commentId,long viewerId) {
        // post_id는 변경할 수 없는 값이다. 이후 게시글 → 댓글을 현재 읽기로 잠근다.
        long postId = repository.postId(commentId);
        if (!repository.readablePost(postId,true)) throw CommentRepository.notFound();
        var comment = repository.lockComment(commentId,postId);
        if (comment.deletedAt() != null) throw CommentRepository.notFound();
        if (comment.authorId() != viewerId) {
            throw new ApiException(HttpStatus.FORBIDDEN,"NOT_COMMENT_AUTHOR","본인 댓글만 변경할 수 있습니다.");
        }
    }

    private CommentRepository.Viewer requireViewer(String loginId,boolean write) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        var viewer = repository.viewer(loginId,write).orElseThrow(ApiException::unauthorized);
        if (write && "SUSPENDED".equals(viewer.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN,"WRITING_RESTRICTED","댓글 작성 및 변경이 제한된 계정입니다.");
        }
        if (!"ACTIVE".equals(viewer.status())) throw ApiException.unauthorized();
        return viewer;
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
