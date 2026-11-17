package com.boxbox.garage.profile;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.profile.dto.ProfileResponse;
import com.boxbox.garage.profile.dto.ProfileUpdateResponse;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {
    private final ProfileRepository profileRepository;
    private final ProfileAvatarPolicy avatarPolicy;

    public ProfileResponse getProfile(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw ApiException.unauthorized();
        }
        return profileRepository.findActiveProfile(loginId).orElseThrow(ApiException::unauthorized);
    }

    @Transactional
    public ProfileUpdateResponse updateProfile(String loginId, Map<String, Object> body) {
        if (loginId == null || loginId.isBlank() || !profileRepository.lockActiveUser(loginId)) {
            throw ApiException.unauthorized();
        }
        ProfileResponse current = getProfile(loginId);
        ProfilePatch patch = ProfilePatch.from(body);
        if (patch.fields().isEmpty()) {
            return current.updatedProfile();
        }
        String nickname = patch.has("nickname") ? patch.nickname() : current.nickname();
        long userId = Long.parseLong(current.userId());
        if (patch.has("nickname") && profileRepository.nicknameTaken(nickname, userId)) {
            throw ProfileRepository.nicknameTaken();
        }
        Long currentTeam = current.favoriteTeam() == null ? null : Long.valueOf(current.favoriteTeam().teamId());
        Long currentDriver = current.favoriteDriver() == null ? null : Long.valueOf(current.favoriteDriver().driverId());
        Long teamId = patch.has("favoriteTeamId") ? patch.favoriteTeamId() : currentTeam;
        Long driverId = patch.has("favoriteDriverId") ? patch.favoriteDriverId() : currentDriver;
        if (teamId != null && !profileRepository.teamExists(teamId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀을 찾을 수 없습니다.");
        }
        if (!Objects.equals(teamId, currentTeam) && currentDriver != null && !patch.has("favoriteDriverId")) {
            throw ApiException.invalidRequest("팀을 변경할 때는 선수도 재선택하거나 해제해 주세요.");
        }
        if (driverId != null) {
            Long driverTeam = profileRepository.findDriverTeam(driverId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "DRIVER_NOT_FOUND", "선수를 찾을 수 없습니다.")).teamId();
            if (teamId == null || !Objects.equals(teamId, driverTeam)) {
                throw ApiException.invalidRequest("선수는 선택한 팀 소속이어야 합니다.");
            }
        }
        String avatarId = patch.has("avatarAssetId")
                ? avatarPolicy.validateOrDefault(patch.avatarAssetId()) : current.avatarAssetId();
        profileRepository.update(userId, nickname, patch.has("bio") ? patch.bio() : current.bio(),
                avatarId, teamId, driverId,
                patch.has("showMarketAssets") ? patch.showMarketAssets() : current.showMarketAssets());
        return getProfile(loginId).updatedProfile();
    }
}
