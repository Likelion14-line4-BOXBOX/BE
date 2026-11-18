package com.boxbox.garage.overview;

import com.boxbox.garage.overview.dto.GarageMarketSummary;
import java.util.Optional;

/** 마켓 담당의 읽기 전용 연동 지점. 공개 여부를 확인한 뒤에만 호출한다. */
public interface GarageMarketSummaryReader {
    /**
     * 원본 조회/평가 데이터 미준비만 empty로 표현한다. 거래 이력/보유 종목이 없는 회원도
     * 실제 잔액으로 평가 가능하면 정상 요약을 반환한다. 순위 미산정/분모0은 해당 필드만 null이다.
     */
    Optional<GarageMarketSummary> find(long userId);
}
