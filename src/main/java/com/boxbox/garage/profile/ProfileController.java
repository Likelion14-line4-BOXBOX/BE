package com.boxbox.garage.profile;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.garage.profile.dto.ProfileResponse;
import com.boxbox.garage.profile.dto.ProfileUpdateResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profileService;

    @GetMapping
    public ApiResponse<ProfileResponse> getProfile(HttpServletRequest request) {
        return ApiResponse.success(profileService.getProfile(requireLoginId(request)));
    }

    @PatchMapping
    public ApiResponse<ProfileUpdateResponse> updateProfile(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.success(profileService.updateProfile(requireLoginId(request), body));
    }

    // 인증 담당이 토큰 검증 후 설정한 Principal만 사용한다. name=loginId는 기존 연결 가정이다.
    private String requireLoginId(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw ApiException.unauthorized();
        }
        return principal.getName();
    }
}
