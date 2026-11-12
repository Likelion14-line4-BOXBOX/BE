package com.boxbox.board;

import com.boxbox.board.dto.BoardResponse;
import com.boxbox.board.dto.BoardTagResponse;
import com.boxbox.board.dto.ItemsResponse;
import com.boxbox.common.api.ApiException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {
    private final BoardRepository boardRepository;
    private final BoardTagRepository boardTagRepository;

    public ItemsResponse<BoardResponse> getBoards(BoardFilter filter, String loginId) {
        BoardRepository.Viewer viewer = resolveViewer(loginId);
        String type = filter.type() == null ? null : filter.type().name();
        var items = boardRepository.findSummaries(type, filter.teamId()).stream()
                .map(board -> new BoardResponse(
                        board.getBoardId().toString(), board.getType(), board.getName(),
                        board.getTeamId() == null ? null : board.getTeamId().toString(),
                        board.getPostCount(), board.getDisplayOrder(),
                        viewer == null ? null : board.getTeamId() != null
                                && Objects.equals(board.getTeamId(), viewer.getFavoriteTeamId())
                ))
                .toList();
        return new ItemsResponse<>(items);
    }

    public ItemsResponse<BoardTagResponse> getTags(long boardId, String loginId) {
        resolveViewer(loginId);
        if (!boardRepository.existsByIdAndActiveTrue(boardId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시판을 찾을 수 없습니다.");
        }
        var items = boardTagRepository.findByBoardIdOrderByDisplayOrderAscIdAsc(boardId).stream()
                .map(tag -> new BoardTagResponse(tag.getId().toString(), tag.getName(),
                        tag.getGroup(), tag.isActive(), tag.getDisplayOrder()))
                .toList();
        return new ItemsResponse<>(items);
    }

    private BoardRepository.Viewer resolveViewer(String loginId) {
        if (loginId == null) {
            return null;
        }
        return boardRepository.findViewer(loginId).orElseThrow(ApiException::unauthorized);
    }
}
