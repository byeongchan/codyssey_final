package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotSelectionTest {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    final String reference = "2026-10-22T03:00:00Z";
    JsonNode product;
    List<JsonNode> categories;
    @BeforeEach void setup() throws Exception {
        product = mapper.readTree("{\"id\":\"p\",\"title\":\"상품\",\"optionKey\":\"black\",\"categoryId\":\"audio\"}");
        categories = List.of(mapper.readTree("{\"id\":\"audio\",\"name\":\"음향\"}"));
    }
    SnapshotArchive.Entry entry(String option, String observedAt, long price) throws Exception {
        var item = product.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("optionKey", option);
        var content = mapper.createObjectNode().put("source", "fixture").put("synthetic", true);
        content.set("product", item);
        content.set("offers", mapper.readTree("[{\"id\":\"o\",\"productId\":\"p\",\"store\":\"demo\",\"currency\":\"KRW\",\"availability\":\"IN_STOCK\",\"productPrice\":" + price + ",\"shippingFee\":0,\"unconditionalDiscount\":0,\"conditionalBenefits\":[],\"observedAt\":\"" + observedAt + "\"}]"));
        content.set("dailyPrices", mapper.readTree("[{\"productId\":\"p\",\"date\":\"2026-10-22\",\"comparablePrice\":" + price + ",\"validSamples\":1}]"));
        String id = FixtureSnapshots.hash(mapper.writeValueAsBytes(mapper.convertValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})));
        return new SnapshotArchive.Entry(id, id, content);
    }
    @Test void selectsWholeNewestObservationBundleAndNeverMixesOptions() throws Exception {
        var older = entry("black", "2026-10-22T01:00:00Z", 10000);
        var newer = entry("black", "2026-10-22T02:00:00Z", 9000);
        var other = entry("white", "2026-10-22T03:00:00Z", 1);
        var selected = new SnapshotSelection(mapper).select(product, categories, reference, List.of(newer, other, older));
        assertEquals(newer.id(), selected.snapshotId());
        assertEquals(9000, selected.content().path("dailyPrices").get(0).path("comparablePrice").asInt());
        assertNull(new SnapshotSelection(mapper).select(product, categories, reference, List.of(other)));
    }
    @Test void tamperedAndFutureSnapshotsFailRatherThanSilentlyFallingBack() throws Exception {
        var valid = entry("black", "2026-10-22T01:00:00Z", 10000);
        var selector = new SnapshotSelection(mapper);
        var tampered = new SnapshotArchive.Entry("wrong-id", "wrong-id", valid.content());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> selector.select(product, categories, reference, List.of(valid, tampered))).getStatusCode().value());
        var future = entry("black", "2026-10-23T01:00:00Z", 9000);
        assertThrows(ResponseStatusException.class, () -> selector.select(product, categories, reference, List.of(future)));
    }
    @Test void equalTimesUseDeterministicContentIdRegardlessOfQueryOrder() throws Exception {
        var a = entry("black", "2026-10-22T01:00:00Z", 10000); var b = entry("black", "2026-10-22T01:00:00Z", 9000);
        var selector = new SnapshotSelection(mapper);
        assertEquals(selector.select(product, categories, reference, List.of(a, b)).snapshotId(), selector.select(product, categories, reference, List.of(b, a)).snapshotId());
    }
    SnapshotArchive.Entry aggregated(SnapshotArchive.Entry original, boolean tampered) throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) original.content().deepCopy();
        data.put("dailyPriceAggregationVersion", 1L);
        var offers = new ArrayList<JsonNode>(); data.path("offers").forEach(offers::add);
        var seeds = new ArrayList<JsonNode>(); data.path("dailyPrices").forEach(seeds::add);
        data.set("dailyPrices", mapper.valueToTree(DailyPriceAggregation.aggregate("p", offers, seeds)));
        if (tampered) ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("dailyPrices").get(0)).put("validSamples", 5);
        String id = FixtureSnapshots.hash(mapper.writeValueAsBytes(mapper.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})));
        // Mimic Firestore's Long values, including price, count and aggregation version.
        var firestoreMapper = mapper.copy().enable(DeserializationFeature.USE_LONG_FOR_INTS);
        JsonNode decoded = firestoreMapper.readTree(mapper.writeValueAsBytes(data));
        return new SnapshotArchive.Entry(id, id, decoded);
    }
    @Test void aggregatedBundleWinsLegacyTieAndAcceptsFirestoreLongNumbers() throws Exception {
        var legacy = entry("black", "2026-10-22T01:00:00Z", 10000);
        var current = aggregated(legacy, false);
        var selector = new SnapshotSelection(mapper);
        assertEquals(current.id(), selector.select(product, categories, reference, List.of(legacy, current)).snapshotId());
        assertEquals(current.id(), selector.select(product, categories, reference, List.of(current, legacy)).snapshotId());
    }
    @Test void rejectsIncorrectAggregateEvenWithMatchingContentHash() throws Exception {
        var incorrect = aggregated(entry("black", "2026-10-22T01:00:00Z", 10000), true);
        assertThrows(ResponseStatusException.class, () -> new SnapshotSelection(mapper).select(product, categories, reference, List.of(incorrect)));
    }
}
