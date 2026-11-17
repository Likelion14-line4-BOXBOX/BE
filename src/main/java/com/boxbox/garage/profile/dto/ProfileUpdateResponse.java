package com.boxbox.garage.profile.dto;

public record ProfileUpdateResponse(
        String userId, String nickname, String bio, String avatarAssetId,
        ProfileResponse.Team favoriteTeam, ProfileResponse.Driver favoriteDriver,
        boolean showMarketAssets
) {}
