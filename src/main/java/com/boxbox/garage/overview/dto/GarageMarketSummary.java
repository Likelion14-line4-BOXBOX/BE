package com.boxbox.garage.overview.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 마켓 담당이 평가한 동일 시점의 결과다. 개러지는 금액/수익률/순위를 재계산하지 않는다. */
public record GarageMarketSummary(long totalAssets, BigDecimal returnRate, Integer rank, Instant snapshotAt) {
    public GarageMarketSummary {
        if (totalAssets < 0 || (rank != null && rank < 1)) throw new IllegalArgumentException("잘못된 마켓 요약입니다.");
        Objects.requireNonNull(snapshotAt, "평가 기준 시각이 필요합니다.");
    }
}
