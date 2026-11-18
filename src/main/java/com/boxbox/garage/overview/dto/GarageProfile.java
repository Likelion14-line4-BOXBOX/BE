package com.boxbox.garage.overview.dto;

import com.boxbox.garage.profile.dto.ProfileResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

public record GarageProfile(String userId, String nickname, String bio, String avatarAssetId,
                            ProfileResponse.Team favoriteTeam, ProfileResponse.Driver favoriteDriver,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean showMarketAssets) {
    public static GarageProfile from(ProfileResponse source, boolean owner) {
        return new GarageProfile(source.userId(), source.nickname(), source.bio(), source.avatarAssetId(),
                source.favoriteTeam(), source.favoriteDriver(), owner ? source.showMarketAssets() : null);
    }
}
