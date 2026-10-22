package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.HashSet;
import java.util.Set;

/** 공급자에서 들어온 데이터와 fixture에 공통으로 적용할 가격·시각 규칙. */
public final class DataPolicy {
    public static final Duration MAX_AGE = Duration.ofHours(24);
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // JavaScript에서도 손실 없이 처리할 수 있도록 KRW 금액의 상한을 제한한다.
    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    private DataPolicy() {}

    public static boolean stale(Instant observedAt, Instant referenceTime) {
        return observedAt.plus(MAX_AGE).isBefore(referenceTime);
    }
    public static boolean historyStale(String date, Instant referenceTime) {
        // 일 대표값은 해당 날짜의 종료까지 관측된 집계값으로 취급한다.
        Instant dayEnd = LocalDate.parse(date).plusDays(1).atStartOfDay(SEOUL).toInstant().minusNanos(1);
        return stale(dayEnd, referenceTime);
    }
    public static long amount(JsonNode value, String field, boolean positive) {
        require(value.isIntegralNumber() && value.canConvertToLong(), field + ": 정수 KRW 금액이 필요합니다");
        long result = value.asLong();
        require(result >= (positive ? 1 : 0) && result <= MAX_AMOUNT, field + ": 금액 범위를 벗어났습니다");
        return result;
    }
    public static Instant instant(JsonNode value, String field) {
        require(value.isTextual(), field + ": 시간대가 있는 ISO-8601 시각이 필요합니다");
        try { return OffsetDateTime.parse(value.asText()).toInstant(); }
        catch (DateTimeException ex) { throw new IllegalArgumentException(field + ": 시각 형식이 올바르지 않습니다"); }
    }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual() && !node.path(field).asText().isBlank(), field + ": 값이 필요합니다");
        return node.path(field).asText();
    }
    private static void array(JsonNode node, String field) { require(node.path(field).isArray(), field + ": 배열이 필요합니다"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    public static void validate(JsonNode catalog, JsonNode prices) {
        require(catalog != null && catalog.isObject() && prices != null && prices.isObject(), "fixture 최상위 객체가 필요합니다");
        require(catalog.path("schemaVersion").isInt() && catalog.path("schemaVersion").asInt() == 1, "지원하지 않는 schemaVersion");
        Instant reference = instant(catalog.path("referenceTime"), "referenceTime");
        LocalDate referenceDate = reference.atZone(SEOUL).toLocalDate();
        array(catalog, "products"); array(catalog, "categories"); array(prices, "offers"); array(prices, "dailyPrices");
        Set<String> categories = new HashSet<>(), productIds = new HashSet<>(), offerIds = new HashSet<>(), dayKeys = new HashSet<>();
        for (JsonNode category : catalog.path("categories")) {
            require(categories.add(text(category, "id")), "중복 카테고리 ID"); text(category, "name");
        }
        for (JsonNode product : catalog.path("products")) {
            require(productIds.add(text(product, "id")), "중복 상품 ID");
            text(product, "title"); text(product, "optionKey");
            require(categories.contains(text(product, "categoryId")), "알 수 없는 상품 카테고리");
        }
        for (JsonNode offer : prices.path("offers")) {
            String context = "offers/" + text(offer, "id");
            require(offerIds.add(offer.path("id").asText()), context + ": 중복 ID");
            require(productIds.contains(text(offer, "productId")), context + ": 알 수 없는 상품");
            text(offer, "store");
            require("KRW".equals(text(offer, "currency")), context + ": KRW만 지원합니다");
            require(Set.of("IN_STOCK", "OUT_OF_STOCK").contains(text(offer, "availability")), context + ": 잘못된 재고 상태");
            long price = amount(offer.path("productPrice"), context + "/productPrice", true);
            require(offer.has("shippingFee"), context + ": shippingFee는 정수 또는 null이어야 합니다");
            if (!offer.path("shippingFee").isNull()) amount(offer.path("shippingFee"), context + "/shippingFee", false);
            long discount = amount(offer.path("unconditionalDiscount"), context + "/unconditionalDiscount", false);
            require(discount < price, context + ": 무조건 할인은 상품가 미만이어야 합니다");
            array(offer, "conditionalBenefits");
            for (JsonNode benefit : offer.path("conditionalBenefits")) {
                text(benefit, "label"); amount(benefit.path("amount"), context + "/conditionalBenefit", false);
            }
            require(!instant(offer.path("observedAt"), context + "/observedAt").isAfter(reference), context + ": 미래 관측 시각");
        }
        for (JsonNode point : prices.path("dailyPrices")) {
            String productId = text(point, "productId");
            require(productIds.contains(productId), "dailyPrices: 알 수 없는 상품");
            String date = text(point, "date");
            LocalDate parsed;
            try { parsed = LocalDate.parse(date); }
            catch (DateTimeException ex) { throw new IllegalArgumentException("dailyPrices: 날짜 형식이 올바르지 않습니다"); }
            require(!parsed.isAfter(referenceDate), "dailyPrices: 미래 날짜");
            require(dayKeys.add(productId + ":" + date), "dailyPrices: 상품별 날짜 중복");
            amount(point.path("comparablePrice"), "dailyPrices/comparablePrice", true);
            require(point.path("validSamples").isIntegralNumber() && point.path("validSamples").canConvertToInt()
                    && point.path("validSamples").asInt() > 0, "dailyPrices: 양의 표본 수가 필요합니다");
        }
    }
}
