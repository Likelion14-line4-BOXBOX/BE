package com.boxbox.garage.profile;

import com.boxbox.common.api.ApiException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 실제 FE 프리셋 ID를 설정으로 연결한다. 파일/URL/이미지 카탈로그는 관리하지 않는다. */
@Component
public class ProfileAvatarPolicy {
    public static final String DEFAULT_AVATAR = "avatar_default";
    private final Set<String> allowedIds;

    public ProfileAvatarPolicy(@Value("${boxbox.profile.allowed-avatar-ids:avatar_default}") Set<String> allowedIds) {
        this.allowedIds = Set.copyOf(allowedIds);
    }

    public String validateOrDefault(String avatarAssetId) {
        if (avatarAssetId == null || DEFAULT_AVATAR.equals(avatarAssetId)) {
            return DEFAULT_AVATAR;
        }
        if (avatarAssetId.length() > 80 || !allowedIds.contains(avatarAssetId)) {
            throw ApiException.invalidRequest("허용되지 않은 프로필 프리셋입니다.");
        }
        return avatarAssetId;
    }
}
