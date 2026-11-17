package com.boxbox.auth;

import com.boxbox.common.api.ApiErrorResponse;
import com.boxbox.common.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-100)
@RequiredArgsConstructor
class AuthHttpFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
    private static final Set<String> HEADERS = Set.of("authorization", "content-type", "last-event-id", "accept", "idempotency-key");
    private final AuthSettings settings;
    private final AuthService service;
    private final JsonMapper json;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/api/") && !request.getRequestURI().startsWith(request.getContextPath() + "/api/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (path.startsWith("/api/auth/")) response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            if (origin != null) {
                if (Collections.list(request.getHeaders("Origin")).size() != 1 || !settings.origins.contains(origin))
                    throw new ApiException(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "허용되지 않은 요청 출처입니다.");
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.addHeader("Vary", "Origin");
            }
            if ("OPTIONS".equals(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null) {
                if (origin == null || !METHODS.contains(request.getHeader("Access-Control-Request-Method"))) throw csrf();
                String headers = request.getHeader("Access-Control-Request-Headers");
                if (headers != null) for (String header : headers.split(",")) if (!HEADERS.contains(header.trim().toLowerCase(Locale.ROOT))) throw csrf();
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Last-Event-ID, Accept, Idempotency-Key");
                response.addHeader("Vary", "Access-Control-Request-Method");
                response.addHeader("Vary", "Access-Control-Request-Headers");
                response.setStatus(204);
                return;
            }
            boolean cookieEndpoint = path.equals("/api/auth/refresh") || path.equals("/api/auth/logout");
            if ("POST".equals(request.getMethod()) && cookieEndpoint && request.getCookies() != null && origin == null) throw csrf();
            // 인증 API는 refresh 쿠키/가입·로그인 정보로 인증한다. 만료된 access 헤더가 재발급을 막지 않는다.
            if (!path.startsWith("/api/auth/")) {
                var headers = Collections.list(request.getHeaders("Authorization"));
                if (!headers.isEmpty()) {
                    String header = headers.getFirst();
                    if (headers.size() != 1 || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7)
                        throw ApiException.unauthorized();
                    AuthPrincipal principal = service.principal(header.substring(7));
                    request = new HttpServletRequestWrapper(request) {
                        @Override public Principal getUserPrincipal() { return principal; }
                        @Override public String getRemoteUser() { return principal.getName(); }
                        @Override public boolean isUserInRole(String role) { return false; }
                    };
                }
            }
        } catch (ApiException error) {
            fail(response, error.getStatus().value(), error.getCode(), error.getMessage());
            return;
        } catch (DataAccessException error) {
            fail(response, 500, "INTERNAL_SERVER_ERROR", "요청을 처리하지 못했습니다.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void fail(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(response.getWriter(), ApiErrorResponse.of(code, message));
    }
    private static ApiException csrf() { return new ApiException(HttpStatus.FORBIDDEN, "CSRF_VALIDATION_FAILED", "요청 출처를 확인해 주세요."); }
}
