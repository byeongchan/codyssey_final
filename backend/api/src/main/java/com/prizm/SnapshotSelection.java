package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Component
public class SnapshotSelection {
    public record Selected(String snapshotId, Instant observedAt, String lastDate, JsonNode content) {}
    private final ObjectMapper mapper;
    public SnapshotSelection(ObjectMapper mapper) { this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); }
    public Selected select(JsonNode product, List<JsonNode> categories, String reference, List<SnapshotArchive.Entry> entries) {
        var candidates = new ArrayList<Selected>();
        try {
            for (var entry : entries) {
                var data = entry.content();
                if (data == null || !data.isObject() || !"fixture".equals(data.path("source").asText()) || !data.path("synthetic").isBoolean() || !data.path("synthetic").asBoolean()
                        || !entry.id().equals(entry.contentDigest()) || !entry.id().equals(FixtureSnapshots.hash(mapper.writeValueAsBytes(mapper.convertValue(data, new TypeReference<Map<String, Object>>() {}))))
                        || !product.path("id").asText().equals(data.path("product").path("id").asText())) throw invalid();
                // Old option variants are archived but must never supply prices for the current option.
                if (!product.path("optionKey").asText().equals(data.path("product").path("optionKey").asText())) continue;
                if (!product.path("categoryId").asText().equals(data.path("product").path("categoryId").asText()) || data.path("offers").size() > 200 || data.path("dailyPrices").size() > 90) throw invalid();
                var catalog = mapper.createObjectNode().put("schemaVersion", 1).put("referenceTime", reference);
                catalog.set("categories", mapper.valueToTree(categories)); catalog.set("products", mapper.valueToTree(List.of(data.path("product"))));
                var prices = mapper.createObjectNode(); prices.set("offers", data.path("offers")); prices.set("dailyPrices", data.path("dailyPrices"));
                DataPolicy.validate(catalog, prices);
                if (data.has("dailyPriceAggregationVersion")) {
                    if (!data.path("dailyPriceAggregationVersion").isIntegralNumber()
                            || !data.path("dailyPriceAggregationVersion").canConvertToInt()
                            || data.path("dailyPriceAggregationVersion").asInt() != DailyPriceAggregation.VERSION) throw invalid();
                    var offers = new ArrayList<JsonNode>(); data.path("offers").forEach(offers::add);
                    var days = new ArrayList<JsonNode>(); data.path("dailyPrices").forEach(days::add);
                    // Firestore decodes every integer as Long; compare normalized JSON numbers.
                    if (!mapper.readTree(mapper.writeValueAsBytes(DailyPriceAggregation.aggregate(product.path("id").asText(), offers, days)))
                            .equals(mapper.readTree(mapper.writeValueAsBytes(data.path("dailyPrices"))))) throw invalid();
                }
                Instant observed = Instant.MIN;
                for (var offer : data.path("offers")) {
                    var time = Instant.parse(offer.path("observedAt").asText()); if (time.isAfter(observed)) observed = time;
                }
                String lastDate = "";
                for (var point : data.path("dailyPrices")) if (point.path("date").asText().compareTo(lastDate) > 0) lastDate = point.path("date").asText();
                candidates.add(new Selected(entry.id(), observed, lastDate, data.deepCopy()));
            }
            return candidates.stream().max(Comparator.comparing(Selected::observedAt)
                .thenComparingInt(s -> s.content().path("dailyPriceAggregationVersion").asInt(0))
                .thenComparing(Selected::lastDate).thenComparing(Selected::snapshotId)).orElse(null);
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw invalid(); }
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.CONFLICT, "스냅샷 내용 해시·상품·가격·시각 검증에 실패했습니다"); }
}
