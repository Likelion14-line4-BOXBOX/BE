package com.boxbox.garage.overview;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.overview.dto.GarageMarketSummary;
import com.boxbox.garage.overview.dto.GarageProfile;
import com.boxbox.garage.overview.dto.GarageResponse;
import com.boxbox.garage.profile.ProfileRepository;
import com.boxbox.garage.stamp.RaceStampService;
import com.boxbox.radio.board.BoardFilter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GarageService {
    private final GarageViewerRepository viewers;
    private final ProfileRepository profiles;
    private final RaceStampService stamps;
    private final ObjectProvider<GarageMarketSummaryReader> market;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GarageResponse get(String loginId, String userId, Map<String, String> params) {
        if (loginId == null || loginId.isBlank()) throw ApiException.unauthorized();
        long viewerId = viewers.viewerId(loginId).orElseThrow(ApiException::unauthorized);
        long targetId = BoardFilter.parseId(userId, "userId");
        if (!params.isEmpty()) throw ApiException.invalidRequest("개러지 조회에는 조회 조건을 지정하지 않습니다.");
        var profile = profiles.findGarageProfile(targetId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "유저를 찾을 수 없습니다."));
        boolean owner = viewerId == targetId;
        // 비공개 타인 조회에서는 원본 제공자의 존재나 준비 상태도 확인하지 않는다.
        GarageMarketSummary summary = owner || profile.showMarketAssets() ? marketSummary(targetId) : null;
        var board = stamps.list(loginId, Long.toString(targetId), Map.of());
        return new GarageResponse(GarageProfile.from(profile, owner), summary,
                new GarageResponse.StampSummary(board.season(), board.watchedCount(), board.totalRaces()), owner);
    }

    private GarageMarketSummary marketSummary(long targetId) {
        var reader = market.getIfAvailable();
        if (reader == null) throw unavailable();
        return reader.find(targetId).orElseThrow(GarageService::unavailable);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_SUMMARY_UNAVAILABLE", "자산 요약 데이터가 아직 준비되지 않았습니다.");
    }
}
