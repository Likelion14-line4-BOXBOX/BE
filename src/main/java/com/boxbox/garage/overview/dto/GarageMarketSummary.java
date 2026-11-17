package com.boxbox.garage.overview.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/** 마켓의 현재 평가와 최근 공개된 시즌 순위다. 개러지는 금액/수익률/순위를 재계산하지 않는다. */
public record GarageMarketSummary(BigInteger totalAssets, BigDecimal returnRate, Long rank, Instant snapshotAt) {
    public GarageMarketSummary {
        if (totalAssets.signum() < 0 || (rank != null && rank < 1)) throw new IllegalArgumentException("잘못된 마켓 요약입니다.");
        Objects.requireNonNull(snapshotAt, "평가 기준 시각이 필요합니다.");
    }
}
