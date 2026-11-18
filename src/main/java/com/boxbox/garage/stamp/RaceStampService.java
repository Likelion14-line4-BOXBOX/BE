package com.boxbox.garage.stamp;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.stamp.dto.RaceStampsResponse;
import com.boxbox.radio.board.BoardFilter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RaceStampService {
    private final RaceStampRepository repository;
    private final int activeSeason;

    RaceStampService(RaceStampRepository repository, @Value("${boxbox.chat.active-season:2026}") int activeSeason) {
        if (activeSeason < 1000 || activeSeason > 9999) throw new IllegalArgumentException("활성 시즌은 네 자리 연도여야 합니다.");
        this.repository = repository;
        this.activeSeason = activeSeason;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RaceStampsResponse list(String loginId, String userId, Map<String, String> params) {
        if (loginId != null && (loginId.isBlank() || !repository.authenticated(loginId))) throw ApiException.unauthorized();
        long target = BoardFilter.parseId(userId, "userId");
        if (!Set.of("season").containsAll(params.keySet())) throw ApiException.invalidRequest("지원하지 않는 조회 조건입니다.");
        int season = activeSeason;
        if (params.containsKey("season")) {
            String value = params.get("season");
            if (value == null || !value.matches("[0-9]{4}") || (season = Integer.parseInt(value)) < 1000) {
                throw ApiException.invalidRequest("season은 네 자리 시즌 연도여야 합니다.");
            }
        }
        if (!repository.userExists(target)) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "유저를 찾을 수 없습니다.");
        var items = List.copyOf(repository.items(target, season));
        int watched = (int) items.stream().filter(item -> item.recordedAt() != null).count();
        return new RaceStampsResponse(season, watched, items.size(), items);
    }
}
