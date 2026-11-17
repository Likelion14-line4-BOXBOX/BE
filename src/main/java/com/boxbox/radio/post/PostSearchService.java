package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.post.dto.BoardPostsResponse;
import com.boxbox.radio.post.dto.PostSearchResponse;
import com.boxbox.radio.post.dto.PostSummaryResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PostSearchService {
    private final PostSearchRepository repository;
    private final PostRepository posts;

    BoardPostsResponse boardPosts(PostSearchFilter filter, String loginId) {
        Long viewer = viewer(loginId);
        String type = repository.validate(filter);
        var page = page(filter, viewer);
        PostSummaryResponse popular = "FREE".equals(type) || "BEGINNER".equals(type)
                ? repository.popular(filter, page.anchor(), page.maxId(), viewer) : null;
        return new BoardPostsResponse(page.rows().stream().map(PostSearchRepository.Row::summary).toList(),
                page.nextCursor(), page.hasNext(), page.total(), popular);
    }

    PostSearchResponse search(PostSearchFilter filter, String loginId) {
        Long viewer = viewer(loginId);
        repository.validate(filter);
        var page = page(filter, viewer);
        return new PostSearchResponse(page.rows().stream().map(row ->
                new PostSearchResponse.Item(row.summary(), row.boardName(), row.archived())).toList(),
                page.total(), page.nextCursor(), page.hasNext());
    }

    private Page page(PostSearchFilter filter, Long viewer) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var cursor = filter.cursor() == null ? null : PostSearchCursor.decode(filter.cursor(), filter, now);
        Instant anchor = cursor == null ? now : cursor.anchor();
        long maxId = cursor == null ? repository.maxId() : cursor.maxId();
        long total = repository.total(filter, anchor, maxId);
        var fetched = repository.page(filter, anchor, maxId, cursor, viewer);
        boolean hasNext = fetched.size() > filter.size();
        var rows = List.copyOf(fetched.subList(0, Math.min(fetched.size(), filter.size())));
        String next = null;
        if (hasNext) {
            PostSummaryResponse last = rows.getLast().summary();
            long score = switch (filter.sort()) {
                case POPULAR -> last.likeCount();
                case COMMENTS -> last.commentCount();
                default -> 0;
            };
            next = new PostSearchCursor(anchor, maxId, score, last.createdAt(), Long.parseLong(last.postId())).encode(filter);
        }
        return new Page(rows, total, hasNext, next, anchor, maxId);
    }

    private Long viewer(String loginId) {
        if (loginId == null) return null;
        return posts.viewer(loginId, false).filter(value -> "ACTIVE".equals(value.status()))
                .orElseThrow(ApiException::unauthorized).id();
    }

    private record Page(List<PostSearchRepository.Row> rows, long total, boolean hasNext, String nextCursor, Instant anchor, long maxId) { }
}
