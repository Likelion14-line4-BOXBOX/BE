package com.boxbox.garage.activity;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.activity.dto.MyPostsResponse;
import com.boxbox.radio.post.PostSummaryReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyPostsService {
    private final MyPostsRepository repository;
    private final PostSummaryReader summaries;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyPostsResponse list(String loginId, Map<String, String> params) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        long viewer = repository.viewer(loginId).orElseThrow(ApiException::unauthorized);
        MyPostsRequest request = MyPostsRequest.from(params);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MyPostsCursor cursor = request.cursor() == null ? null : MyPostsCursor.decode(request.cursor(), viewer, request, now);
        Instant anchor = cursor == null ? now : cursor.anchor();
        long maxId = cursor == null ? repository.maxPostId() : cursor.maxPostId();
        var fetched = repository.page(viewer, request, anchor, maxId, cursor);
        boolean hasNext = fetched.size() > request.size();
        var rows = List.copyOf(fetched.subList(0, Math.min(fetched.size(), request.size())));
        String next = null;
        if (hasNext) {
            var last = rows.getLast();
            next = new MyPostsCursor(anchor, maxId, last.activityAt(), last.postId()).encode(viewer, request);
        }
        return new MyPostsResponse(summaries.findAll(rows.stream().map(MyPostsRepository.Activity::postId).toList(), viewer), next, hasNext);
    }
}
