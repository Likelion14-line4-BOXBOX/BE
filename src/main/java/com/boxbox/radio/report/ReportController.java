package com.boxbox.radio.report;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardViewerResolver;
import com.boxbox.radio.report.dto.ReportResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ReportController {
    private final ReportService service;
    private final BoardViewerResolver viewers;

    @PostMapping("/api/reports")
    ResponseEntity<ApiResponse<ReportResponse>> create(@RequestBody Map<String, Object> body,
            @RequestParam Map<String, String> params, HttpServletRequest request) {
        String loginId = viewers.resolveLoginId(request);
        if (!params.isEmpty()) throw ApiException.invalidRequest("신고 API에는 조회 조건을 지정하지 않습니다.");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(loginId, body)));
    }
}
