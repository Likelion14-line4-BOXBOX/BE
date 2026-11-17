package com.boxbox.common.reference;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record ReferenceData(String referenceVersion, int season, List<Team> teams, List<Driver> drivers) {
    record Team(String teamId, String code, String name, String description, int displayOrder,
                Integer championshipRank, BigDecimal championshipPoints, Instant statsAsOf) {}

    record Driver(String driverId, String code, String name, int number, String teamId, String description,
                  Integer championshipRank, BigDecimal championshipPoints, Instant statsAsOf) {}
}
