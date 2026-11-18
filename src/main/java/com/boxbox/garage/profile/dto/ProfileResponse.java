package com.boxbox.garage.profile.dto;

import java.time.Instant;

public record ProfileResponse(
        String userId, String nickname, String bio, String avatarAssetId,
        Team favoriteTeam, Driver favoriteDriver, boolean showMarketAssets,
        Instant createdAt, boolean onboardingCompleted
) {
    public record Team(String teamId, String name, String nameEn, Integer championshipRank) {}

    public record Driver(String driverId, String name, String nameEn, Integer number,
                         String teamId, String teamName, String teamNameEn, Integer championshipRank) {}

    public ProfileUpdateResponse updatedProfile() {
        return new ProfileUpdateResponse(userId, nickname, bio, avatarAssetId,
                favoriteTeam, favoriteDriver, showMarketAssets);
    }
}
