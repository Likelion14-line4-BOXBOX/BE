package com.boxbox.board;

import com.boxbox.board.dto.BoardResponse;
import com.boxbox.board.dto.BoardTagResponse;
import com.boxbox.board.dto.ItemsResponse;
import com.boxbox.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {
    private final BoardService boardService;
    private final BoardViewerResolver viewerResolver;

    @GetMapping
    public ApiResponse<ItemsResponse<BoardResponse>> getBoards(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String teamId,
            HttpServletRequest request
    ) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(boardService.getBoards(BoardFilter.from(type, teamId), loginId));
    }

    @GetMapping("/{boardId}/tags")
    public ApiResponse<ItemsResponse<BoardTagResponse>> getTags(
            @PathVariable String boardId, HttpServletRequest request
    ) {
        String loginId = viewerResolver.resolveLoginId(request);
        return ApiResponse.success(boardService.getTags(BoardFilter.parseId(boardId, "boardId"), loginId));
    }
}
