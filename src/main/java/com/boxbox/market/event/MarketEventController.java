package com.boxbox.market.event;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiErrorResponse;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiExceptionHandler;
import com.boxbox.market.round.MarketRoundService;
import com.boxbox.radio.board.BoardViewerResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
class MarketEventController {
    private final MarketEventStream stream;
    private final BoardViewerResolver viewers;
    private final ApiExceptionHandler errors;

    @GetMapping("/api/market/rounds/{roundId}/events")
    ResponseEntity<SseEmitter> subscribe(@PathVariable String roundId,
            @RequestHeader(value = "Last-Event-ID", required = false) String cursor, HttpServletRequest request) {
        viewers.resolveLoginId(request);
        var member = request.getUserPrincipal() instanceof AuthPrincipal authenticated ? authenticated : null;
        if (member != null && !member.isValid()) {
            throw ApiException.unauthorized();
        }
        try {
            String accept = request.getHeader(HttpHeaders.ACCEPT);
            if (accept == null || MediaType.parseMediaTypes(accept).stream()
                    .noneMatch(type -> MediaType.TEXT_EVENT_STREAM.equalsTypeAndSubtype(type)
                            && type.getQualityValue() > 0)) {
                throw ApiException.invalidRequest("Accept: text/event-stream이 필요합니다.");
            }
        } catch (InvalidMediaTypeException error) {
            throw ApiException.invalidRequest("Accept 헤더를 확인해 주세요.");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Accel-Buffering", "no")
                .body(stream.subscribe(MarketRoundService.parseRoundId(roundId), cursor,
                        member == null ? () -> true : member::isValid));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> error(ApiException exception) {
        var response = errors.handleApiException(exception);
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }
}
