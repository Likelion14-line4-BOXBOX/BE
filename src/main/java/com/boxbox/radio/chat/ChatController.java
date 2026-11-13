package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.chat.dto.ChatResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ChatController {
    private final ChatService service;
    private final BoardViewerResolver viewers;

    @GetMapping("/api/chat-rooms")
    ApiResponse<ChatResponses.RoomPage> rooms(@RequestParam Map<String, String> params, HttpServletRequest request) {
        return ApiResponse.success(service.rooms(params, viewers.resolveLoginId(request)));
    }

    @GetMapping("/api/chat-rooms/{roomId}")
    ApiResponse<ChatResponses.RoomDetail> detail(@PathVariable String roomId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        return ApiResponse.success(service.detail(BoardFilter.parseId(roomId, "roomId"), params, loginId));
    }

    @GetMapping("/api/chat-rooms/{roomId}/messages")
    ChatResponses.History history(@PathVariable String roomId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        return service.history(BoardFilter.parseId(roomId, "roomId"), params, loginId);
    }
}
