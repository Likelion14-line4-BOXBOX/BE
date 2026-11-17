package com.boxbox.race;

import com.boxbox.race.RaceScheduleService.RaceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.boxbox.race.RaceScheduleService.instant;

@Repository
@RequiredArgsConstructor
class RaceDetailRepository {
    private final JdbcClient jdbc;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RaceDetail> findById(String raceId) {
        if (!raceId.matches("[1-9][0-9]{0,19}")) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT id, season_year, round_number, name, country_name, circuit_name, circuit_key,
                               scheduled_start_at, actual_end_at, status, updated_at
                        FROM races
                        WHERE id = :raceId
                        """)
                .param("raceId", new BigDecimal(raceId))
                .query((row, rowNumber) -> new Race(
                        row.getString("id"), row.getInt("season_year"), row.getInt("round_number"),
                        row.getString("name"), row.getString("country_name"), row.getString("circuit_name"),
                        row.getString("circuit_key"), instant(row, "scheduled_start_at"), instant(row, "actual_end_at"),
                        RaceStatus.valueOf(row.getString("status")),
                        instant(row, "updated_at")))
                .optional()
                .map(race -> {
                    List<SessionRow> sessions = findSessions(raceId);
                    List<StoredResult> storedResults = findResults(raceId);
                    ResultStatus resultStatus = storedResults.isEmpty() ? ResultStatus.UNAVAILABLE
                            : storedResults.stream().allMatch(StoredResult::verifiedFinal)
                                    ? ResultStatus.FINAL : ResultStatus.PROVISIONAL;
                    List<Result> results = storedResults.stream().map(StoredResult::result).toList();
                    Instant updatedAt = race.updatedAt();
                    for (SessionRow session : sessions) {
                        if (session.updatedAt().isAfter(updatedAt)) {
                            updatedAt = session.updatedAt();
                        }
                    }
                    for (StoredResult result : storedResults) {
                        if (result.updatedAt().isAfter(updatedAt)) {
                            updatedAt = result.updatedAt();
                        }
                    }
                    return new RaceDetail(race.raceId(), race.season(), race.roundNumber(),
                            race.name(), race.countryName(), race.circuitName(), race.circuitKey(),
                            race.scheduledStartAt(), race.actualEndAt(), race.status(),
                            sessions.stream().map(SessionRow::session).toList(), results, resultStatus, updatedAt);
                });
    }

    private List<SessionRow> findSessions(String raceId) {
        return jdbc.sql("""
                        SELECT id, type, starts_at, ends_at, updated_at
                        FROM race_sessions
                        WHERE race_id = :raceId
                        ORDER BY starts_at, id
                        """)
                .param("raceId", new BigDecimal(raceId))
                .query((row, rowNumber) -> new SessionRow(new Session(row.getString("id"), row.getString("type"),
                        instant(row, "starts_at"), instant(row, "ends_at")), instant(row, "updated_at")))
                .list();
    }

    private List<StoredResult> findResults(String raceId) {
        return jdbc.sql("""
                        SELECT driver_id, finish_rank, classification_status, official_points,
                               result_status, verified_at, updated_at
                        FROM race_results
                        WHERE race_id = :raceId AND result_version = (
                            SELECT MAX(result_version) FROM race_results WHERE race_id = :raceId
                        )
                        ORDER BY finish_rank IS NULL, finish_rank, id
                        """)
                .param("raceId", new BigDecimal(raceId))
                .query((row, rowNumber) -> new StoredResult(new Result(row.getString("driver_id"),
                        row.getObject("finish_rank", Integer.class),
                        ClassificationStatus.valueOf(row.getString("classification_status")),
                        row.getBigDecimal("official_points")),
                        "FINAL".equals(row.getString("result_status")) && row.getObject("verified_at") != null,
                        instant(row, "updated_at")))
                .list();
    }

    enum ResultStatus { UNAVAILABLE, PROVISIONAL, FINAL }
    enum ClassificationStatus { RUNNING, FINISHED, DNF, DNS, DSQ }

    private record Race(String raceId, int season, int roundNumber, String name, String countryName,
                        String circuitName, String circuitKey, Instant scheduledStartAt, Instant actualEndAt,
                        RaceStatus status, Instant updatedAt) {}

    record RaceDetail(String raceId, int season, int roundNumber, String name, String countryName,
                      String circuitName, String circuitKey, Instant scheduledStartAt, Instant actualEndAt,
                      RaceStatus status, List<Session> sessions, List<Result> results, ResultStatus resultStatus,
                      Instant updatedAt) {}

    private record SessionRow(Session session, Instant updatedAt) {}

    private record StoredResult(Result result, boolean verifiedFinal, Instant updatedAt) {}

    record Session(String sessionId, String type, Instant startsAt, Instant endsAt) {}

    record Result(String driverId, Integer finishRank, ClassificationStatus classificationStatus,
                  BigDecimal points) {}
}
