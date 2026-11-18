package com.boxbox.garage.stamp.dto;

import java.time.Instant;
import java.util.List;

public record RaceStampsResponse(int season, int watchedCount, int totalRaces, List<Item> items) {
    public record Item(String raceId, String raceName, String raceNameKo, int roundNumber, String recordType, String source, Instant recordedAt) { }
}
