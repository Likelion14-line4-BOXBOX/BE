package com.boxbox.radio.board.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.post.PostRepository.StoredMedia;
import com.boxbox.radio.board.post.PostRepository.StoredPost;
import com.boxbox.radio.board.post.PostRepository.Viewer;
import com.boxbox.radio.board.post.dto.PostCreatedResponse;
import com.boxbox.radio.board.post.dto.PostDetailResponse;
import com.boxbox.radio.board.post.dto.PostDetailResponse.Media;
import com.boxbox.radio.board.post.dto.PostUpdatedResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PostService {
    private final PostRepository repository;
    private final PostMediaUrl mediaUrl;
    private final Duration viewWindow;

    PostService(PostRepository repository, PostMediaUrl mediaUrl,
                @Value("${boxbox.posts.view-window:PT10M}") Duration viewWindow) {
        if (viewWindow.isNegative() || viewWindow.isZero()) {
            throw new IllegalArgumentException("게시글 조회 중복 제외 기간은 양수여야 합니다.");
        }
        this.repository = repository;
        this.mediaUrl = mediaUrl;
        this.viewWindow = viewWindow;
    }

    public PostCreatedResponse create(long boardId, String loginId, Map<String, Object> body) {
        Viewer viewer = requireViewer(loginId, true);
        PostInput input = PostInput.from(body, true);
        if (!repository.activeBoard(boardId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시판을 찾을 수 없습니다.");
        }
        repository.validateTag(boardId, input.tagIds());
        Instant now = now();
        validateMedia(viewer.id(), null, input.mediaIds(), List.of(), now);
        long postId = repository.insert(boardId, viewer.id(), input, now);
        repository.attach(postId, input.mediaIds(), now);
        return new PostCreatedResponse(Long.toString(postId), Long.toString(boardId), now);
    }

    public PostDetailResponse detail(long postId, String loginId, String anonymousSession) {
        Viewer viewer = loginId == null ? null : requireViewer(loginId, false);
        StoredPost post = repository.lockPost(postId);
        // URL 구성이 실패하면 조회수도 함께 롤백한다. 저장소 파일 조회/서명은 CM09 담당과 연결한다.
        List<Media> media = repository.media(postId).stream().map(file ->
                new Media(Long.toString(file.id()), mediaUrl.resolve(file.objectKey()), file.width(), file.height())).toList();
        Instant now = now();
        String identity = viewer == null ? "session:" + Objects.requireNonNull(anonymousSession) : "user:" + viewer.id();
        long views = repository.countView(post, hash(identity), now, now.minus(viewWindow));
        boolean owner = viewer != null && viewer.id() == post.authorId();
        String content = post.content();
        String preview = content.substring(0, content.offsetByCodePoints(0, Math.min(100, content.codePointCount(0, content.length()))));
        return new PostDetailResponse(Long.toString(postId), Long.toString(post.boardId()), post.title(), preview,
                repository.author(post.authorId()), repository.tags(post.tagId()), media.isEmpty() ? null : media.getFirst().url(),
                repository.reactionCount(postId, "LIKE"), repository.commentCount(postId),
                repository.reactionCount(postId, "BOOKMARK"), views, post.createdAt(),
                viewer == null ? null : repository.reacted(postId, viewer.id(), "LIKE"),
                viewer == null ? null : repository.reacted(postId, viewer.id(), "BOOKMARK"),
                content, media, post.updatedAt(), owner, owner);
    }

    public PostUpdatedResponse update(long postId, String loginId, Map<String, Object> body) {
        Viewer viewer = requireViewer(loginId, true);
        StoredPost post = repository.lockPost(postId);
        requireOwner(post, viewer);
        PostInput input = PostInput.from(body, false);
        if (input.fields().isEmpty()) return new PostUpdatedResponse(Long.toString(postId), post.updatedAt());
        if (input.has("tagIds")) repository.validateTag(post.boardId(), input.tagIds());
        Instant now = now();
        if (input.has("mediaIds")) {
            List<Long> previous = repository.media(postId).stream().map(StoredMedia::id).toList();
            validateMedia(viewer.id(), postId, input.mediaIds(), previous, now);
            repository.detach(postId, now);
            repository.attach(postId, input.mediaIds(), now);
        }
        repository.update(post, input, now);
        return new PostUpdatedResponse(Long.toString(postId), now);
    }

    public void delete(long postId, String loginId) {
        Viewer viewer = requireViewer(loginId, true);
        StoredPost post = repository.lockPost(postId);
        requireOwner(post, viewer);
        List<Long> mediaIds = repository.media(postId).stream().map(StoredMedia::id).toList();
        repository.lockMedia(mediaIds);
        repository.delete(postId, now());
    }

    private Viewer requireViewer(String loginId, boolean write) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        Viewer viewer = repository.viewer(loginId, write).orElseThrow(ApiException::unauthorized);
        if (write && "SUSPENDED".equals(viewer.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WRITING_RESTRICTED", "글 작성 및 변경이 제한된 계정입니다.");
        }
        if (!"ACTIVE".equals(viewer.status())) throw ApiException.unauthorized();
        return viewer;
    }

    private void requireOwner(StoredPost post, Viewer viewer) {
        if (post.authorId() != viewer.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_POST_AUTHOR", "본인 게시글만 변경할 수 있습니다.");
        }
    }

    private void validateMedia(long ownerId, Long postId, List<Long> requested, List<Long> previous, Instant now) {
        TreeSet<Long> all = new TreeSet<>(requested);
        all.addAll(previous);
        Map<Long, StoredMedia> files = repository.lockMedia(List.copyOf(all)).stream()
                .collect(Collectors.toMap(StoredMedia::id, Function.identity()));
        for (Long id : requested) {
            StoredMedia file = files.get(id);
            boolean uploaded = file != null && "UPLOADED".equals(file.status()) && file.postId() == null
                    && (file.expiresAt() == null || file.expiresAt().isAfter(now));
            boolean retained = file != null && postId != null && "ATTACHED".equals(file.status())
                    && postId.equals(file.postId());
            if (file == null || file.ownerId() != ownerId || file.deletedAt() != null
                    || !"POST_IMAGE".equals(file.purpose()) || !(uploaded || retained)) {
                throw ApiException.invalidRequest("본인이 업로드한 사용 가능한 이미지만 첨부할 수 있습니다.");
            }
        }
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private static byte[] hash(String identity) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
