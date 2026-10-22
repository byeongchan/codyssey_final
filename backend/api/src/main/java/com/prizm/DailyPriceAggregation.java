package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;

/** Input must first pass DataPolicy; each offer ID represents one raw observation. */
public final class DailyPriceAggregation {
    public static final int VERSION = 1;
    private DailyPriceAggregation() {}
    public static List<JsonNode> aggregate(String productId, List<JsonNode> offers, List<JsonNode> seeds) {
        var days = new TreeMap<String, JsonNode>();
        for (var seed : seeds) days.put(seed.path("date").asText(), seed.deepCopy());
        var observedDays = new HashSet<String>();
        var minimum = new HashMap<String, Long>();
        var counts = new HashMap<String, Integer>();
        for (var offer : offers) {
            String day = DataPolicy.instant(offer.path("observedAt"), "observedAt").atZone(DataPolicy.SEOUL).toLocalDate().toString();
            observedDays.add(day);
            if (!"IN_STOCK".equals(offer.path("availability").asText()) || !offer.path("shippingFee").isNumber()) continue;
            long price = offer.path("productPrice").asLong() + offer.path("shippingFee").asLong() - offer.path("unconditionalDiscount").asLong();
            if (price < 1 || price > DataPolicy.MAX_AMOUNT) throw new IllegalArgumentException("집계 비교가 범위 초과");
            minimum.merge(day, price, Math::min); counts.merge(day, 1, Integer::sum);
        }
        // Raw observations replace that day's seed even when none is comparable.
        observedDays.forEach(days::remove);
        minimum.forEach((day, price) -> days.put(day, JsonNodeFactory.instance.objectNode()
            .put("id", productId + "-" + day).put("productId", productId).put("date", day)
            .put("comparablePrice", price).put("validSamples", counts.get(day))));
        // Retention is by observed Korean calendar dates; missing dates stay missing.
        while (days.size() > 90) days.pollFirstEntry();
        return List.copyOf(days.values());
    }
}
