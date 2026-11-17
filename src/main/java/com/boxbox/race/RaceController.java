package com.boxbox.race;

import com.boxbox.race.RaceScheduleService.RacePage;
import com.boxbox.race.RaceScheduleService.RaceStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RaceController {
    private static final Logger log = LoggerFactory.getLogger(RaceController.class);
    private final RaceScheduleService raceScheduleService;

    @GetMapping("/api/races")
    public RaceResponse getRaces(@RequestParam(name = "season", required = false) Integer season,
                                 @RequestParam(name = "status", required = false) RaceStatus status,
                                 @RequestParam(name = "cursor", required = false) String cursor,
                                 @RequestParam(name = "size", defaultValue = "20") int size) {
        Meta meta = newMeta();
        int requestedSeason = season == null ? meta.serverTime().atOffset(ZoneOffset.UTC).getYear() : season;
        if (requestedSeason < 1000 || requestedSeason > 9999 || size < 1 || size > 100) {
            throw new IllegalArgumentException("season은 4자리 연도, size는 1~100이어야 합니다.");
        }

        int afterRound = readCursor(cursor, requestedSeason, status);
        RacePage page = raceScheduleService.getRaces(requestedSeason, status, afterRound, size);
        return new RaceResponse(page, meta);
    }

    private int readCursor(String cursor, int season, RaceStatus status) {
        if (cursor == null) {
            return 0;
        }
        String prefix = RaceScheduleService.cursorPrefix(season, status);
        if (cursor.startsWith(prefix)) {
            try {
                int round = Integer.parseInt(cursor.substring(prefix.length()));
                if (round > 0 && cursor.equals(prefix + round)) {
                    return round;
                }
            } catch (NumberFormatException ignored) {
                // Report a malformed cursor using the same error as a filter mismatch.
            }
        }
        throw new IllegalArgumentException("현재 season과 status에 유효한 cursor를 사용해야 합니다.");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception) {
        String message = exception instanceof MethodArgumentTypeMismatchException
                ? "요청 파라미터의 형식이 올바르지 않습니다."
                : exception.getMessage();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(new ApiError("INVALID_REQUEST", message, List.of()), newMeta()));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorResponse> upstreamError(RestClientException exception) {
        Meta meta = newMeta();
        if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 429) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
            HttpHeaders headers = response.getResponseHeaders();
            String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
            }
            return builder.body(new ErrorResponse(
                    new ApiError("RATE_LIMITED", "요청 한도를 초과했습니다.", List.of()), meta));
        }
        log.warn("OpenF1 schedule lookup failed. requestId={}", meta.requestId(), exception);
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                new ApiError("INTERNAL_ERROR", "레이스 일정을 조회할 수 없습니다.", List.of()), meta));
    }

    private Meta newMeta() {
        return new Meta(UUID.randomUUID().toString(), Instant.now());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> databaseError(DataAccessException exception) {
        Meta meta = newMeta();
        log.error("Race lookup failed. requestId={}", meta.requestId(), exception);
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                new ApiError("INTERNAL_ERROR", "레이스 정보를 조회할 수 없습니다.", List.of()), meta));
    }

    record RaceResponse(RacePage data, Meta meta) {}
    record Meta(String requestId, Instant serverTime) {}
    record ErrorResponse(ApiError error, Meta meta) {}
    record ApiError(String code, String message, List<Object> details) {}
}
