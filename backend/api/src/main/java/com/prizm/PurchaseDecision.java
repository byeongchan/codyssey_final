package com.prizm;

import java.math.BigDecimal;

/** The server owns the verdict; an explanation provider cannot override it. */
public final class PurchaseDecision {
    public static final String VERSION = "price-rules-v1";
    public record Decision(String verdict, String reasonCode, String reason) {}
    private PurchaseDecision() {}
    public static Decision decide(Long currentPrice, double averagePrice, int observedDays, boolean stale) {
        if (stale) return new Decision("INSUFFICIENT_DATA", "STALE_EVIDENCE", "가격 또는 가격 이력이 오래되어 판단을 보류합니다.");
        if (currentPrice == null || currentPrice <= 0) return new Decision("INSUFFICIENT_DATA", "NO_CURRENT_PRICE", "구매 가능한 확정 비교가가 없어 판단을 보류합니다.");
        if (observedDays < 7) return new Decision("INSUFFICIENT_DATA", "SHORT_HISTORY", "최근 관측 일수가 부족해 판단을 보류합니다.");
        if (!Double.isFinite(averagePrice) || averagePrice <= 0) return new Decision("INSUFFICIENT_DATA", "NO_AVERAGE", "비교할 평균 가격이 없어 판단을 보류합니다.");
        var price = BigDecimal.valueOf(currentPrice); var average = BigDecimal.valueOf(averagePrice);
        if (price.compareTo(average.multiply(new BigDecimal("0.98"))) <= 0)
            return new Decision("BUY", "BELOW_AVERAGE", "현재 확정 비교가가 관측 평균보다 낮아 구매를 고려할 수 있습니다.");
        if (price.compareTo(average.multiply(new BigDecimal("1.02"))) >= 0)
            return new Decision("WAIT", "ABOVE_AVERAGE", "현재 확정 비교가가 관측 평균보다 높아 관망을 고려할 수 있습니다.");
        return new Decision("NEUTRAL", "NEAR_AVERAGE", "현재 확정 비교가는 관측 평균과 비슷한 수준입니다.");
    }
}
