package com.boxbox.radio.board;

import com.boxbox.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** 인증 담당 계층이 토큰 검증 후 설정한 Principal만 사용한다. name은 loginId다. */
@Component
public class BoardViewerResolver {
    public String resolveLoginId(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            // 검증 계층이 없는 상태에서 Bearer 값을 사용자 ID로 해석하지 않는다.
            if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
                throw ApiException.unauthorized();
            }
            return null;
        }
        String loginId = principal.getName();
        if (loginId == null || loginId.isBlank()) {
            throw ApiException.unauthorized();
        }
        return loginId;
    }
}
