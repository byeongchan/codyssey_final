package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyPriceAggregationTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode offer(String id, String time, int price, Integer shipping, int discount, boolean stock) {
        var value = mapper.createObjectNode().put("id", id).put("productId", "p").put("observedAt", time)
            .put("productPrice", price).put("unconditionalDiscount", discount)
            .put("availability", stock ? "IN_STOCK" : "OUT_OF_STOCK");
        if (shipping == null) value.putNull("shippingFee"); else value.put("shippingFee", shipping);
        return value;
    }
    JsonNode seed(String date) {
        return mapper.createObjectNode().put("productId", "p").put("date", date).put("comparablePrice", 500).put("validSamples", 7);
    }
    @Test void minimumIncludesShippingAndOnlyUnconditionalDiscountAndCountsValidObservations() {
        var a = offer("a", "2026-10-22T01:00:00Z", 100, 20, 10, true);
        a.set("conditionalBenefits", mapper.createArrayNode().add(mapper.createObjectNode().put("amount", 99)));
        var rows = DailyPriceAggregation.aggregate("p", List.of(a,
            offer("b", "2026-10-22T02:00:00Z", 105, 0, 0, true),
            offer("c", "2026-10-22T02:00:00Z", 1, null, 0, true),
            offer("d", "2026-10-22T02:00:00Z", 1, 0, 0, false)), List.of(seed("2026-10-22")));
        assertEquals(1, rows.size()); assertEquals(105, rows.get(0).path("comparablePrice").asLong());
        assertEquals(2, rows.get(0).path("validSamples").asInt());
    }
    @Test void koreanMidnightSeparatesDatesAndDoesNotFillMissingDays() {
        var rows = DailyPriceAggregation.aggregate("p", List.of(
            offer("a", "2026-10-21T14:59:59Z", 100, 0, 0, true),
            offer("b", "2026-10-21T15:00:00Z", 200, 0, 0, true)), List.of(seed("2026-10-19")));
        assertEquals(List.of("2026-10-19", "2026-10-21", "2026-10-22"), rows.stream().map(r -> r.path("date").asText()).toList());
    }
    @Test void invalidDayRemovesSeedAndReplayDoesNotAccumulateSamples() {
        var offers = List.<JsonNode>of(offer("a", "2026-10-22T00:00:00Z", 100, 0, 0, true),
            offer("b", "2026-10-21T00:00:00Z", 100, null, 0, true));
        var first = DailyPriceAggregation.aggregate("p", offers, List.of(seed("2026-10-21")));
        assertEquals(1, first.size()); assertEquals(first, DailyPriceAggregation.aggregate("p", offers, first));
        assertEquals(1, first.get(0).path("validSamples").asInt());
    }
    @Test void keepsLatestNinetyDatesWithoutChangingInputs() {
        var seeds = new ArrayList<JsonNode>();
        for (int i = 0; i < 90; i++) seeds.add(seed(java.time.LocalDate.of(2026, 10, 21).minusDays(i).toString()));
        var rows = DailyPriceAggregation.aggregate("p", List.of(offer("a", "2026-10-22T00:00:00Z", 100, 0, 0, true)), seeds);
        assertEquals(90, rows.size()); assertEquals("2026-10-22", rows.get(89).path("date").asText());
        assertEquals(90, seeds.size());
    }
}
