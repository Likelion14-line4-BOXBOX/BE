package com.boxbox.garage.overview.dto;

public record GarageResponse(GarageProfile profile, GarageMarketSummary marketSummary,
                             StampSummary stampSummary, boolean isOwner) {
    public record StampSummary(int season, int watchedCount, int totalRaces) { }
}
