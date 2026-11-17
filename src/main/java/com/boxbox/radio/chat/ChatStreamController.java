package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiErrorResponse;
import com.boxbox.common.api.ApiExceptionHandler;
import com.boxbox.radio.board.BoardFilter;
import com.boxbox.radio.board.BoardViewerResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
class ChatStreamController {
    private final ChatStreamHub hub;
    private final BoardViewerResolver viewers;
    private final ApiExceptionHandler errors;

    @GetMapping("/api/chat-rooms/{roomId}/events")
    ResponseEntity<SseEmitter> events(@PathVariable String roomId, @RequestHeader(value = "Last-Event-ID", required = false) String cursor,
            @RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        if (!params.isEmpty()) throw ApiException.invalidRequest("SSE 구독에는 조회 조건을 지정하지 않습니다.");
        try {
            String accept = request.getHeader(HttpHeaders.ACCEPT);
            if (accept == null || MediaType.parseMediaTypes(accept).stream().noneMatch(type -> type.getQualityValue() > 0 && type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))) {
                throw ApiException.invalidRequest("Accept: text/event-stream이 필요합니다.");
            }
        } catch (org.springframework.http.InvalidMediaTypeException error) { throw ApiException.invalidRequest("Accept 헤더를 확인해 주세요."); }
        var emitter = hub.subscribe(BoardFilter.parseId(roomId, "roomId"), cursor, loginId,
                loginId == null ? request.getSession(true).getId() : null,
                request.getUserPrincipal() instanceof AuthPrincipal authenticated ? authenticated::isValid : () -> true);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    @ExceptionHandler({ApiException.class, DataAccessException.class})
    ResponseEntity<ApiErrorResponse> error(RuntimeException exception) {
        var response = exception instanceof ApiException api ? errors.handleApiException(api)
                : errors.handleDataAccessException((DataAccessException) exception);
        // 연결 전 오류는 Accept가 SSE여도 기존 JSON 오류 계약으로 응답한다.
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }
}
