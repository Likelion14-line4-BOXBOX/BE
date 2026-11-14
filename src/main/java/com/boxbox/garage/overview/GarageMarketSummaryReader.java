package com.boxbox.garage.overview;

import com.boxbox.garage.overview.dto.GarageMarketSummary;
import java.util.Optional;

/** 마켓 담당의 읽기 전용 연동 지점. 공개 여부를 확인한 뒤에만 호출한다. */
public interface GarageMarketSummaryReader {
    /** 미준비 데이터는 empty로 표현한다. 고정 자산/순위를 만들어 반환하지 않는다. */
    Optional<GarageMarketSummary> find(long userId);
}
