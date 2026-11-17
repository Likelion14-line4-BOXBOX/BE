package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.chat.dto.ChatResponses.Message;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ChatMessageController {
    private final ChatMessageService service;
    private final BoardViewerResolver viewers;

    @PostMapping("/api/chat-rooms/{roomId}/messages")
    ResponseEntity<ApiResponse<Message>> send(@PathVariable String roomId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.send(BoardFilter.parseId(roomId, "roomId"), body, loginId)));
    }

    @DeleteMapping("/api/chat-rooms/{roomId}/messages/{messageId}")
    ResponseEntity<Void> delete(@PathVariable String roomId, @PathVariable String messageId, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        service.delete(BoardFilter.parseId(roomId, "roomId"), BoardFilter.parseId(messageId, "messageId"), loginId);
        return ResponseEntity.noContent().build();
    }
}
