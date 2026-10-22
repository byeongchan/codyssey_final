package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Source quotes never become verified current prices merely because they were stored. */
public final class DanawaObservationPolicy {
    public record Prepared(String id, String digest, Map<String, Object> content, String day,
                           Map<String, Long> samples, Instant observedAt, Instant receivedAt, Long minimum) {}
    private DanawaObservationPolicy() {}
    public static Prepared prepare(ObjectMapper original, JsonNode result, Instant now) {
        var mapper = original.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        try {
            if (!"reef-danawa".equals(result.path("source").asText()) || !result.path("synthetic").isBoolean() || result.path("synthetic").asBoolean()
                || !"offers".equals(result.path("action").asText()) || !result.path("productId").asText().matches("[0-9]{1,30}")
                || !result.path("page").isIntegralNumber() || !result.path("page").canConvertToInt() || result.path("page").asInt() < 1 || result.path("page").asInt() > 20
                || !result.path("hasMore").isBoolean() || !result.path("offers").isArray() || result.path("offers").size() > 1000) throw invalid();
            Instant received = DataPolicy.instant(result.path("receivedAt"), "receivedAt");
            Instant observed = result.path("fetchedAt").isNull() ? null : DataPolicy.instant(result.path("fetchedAt"), "fetchedAt");
            if (received.isAfter(now.plusSeconds(30)) || (observed != null && (observed.isAfter(now) || observed.isAfter(received.plusSeconds(30))))) throw invalid();
            String productId = result.path("productId").asText();
            var unique = new TreeMap<String, JsonNode>(); var samples = new TreeMap<String, Long>();
            for (var row : result.path("offers")) {
                String offerKey = row.path("offerKey").asText();
                if (!row.isObject() || !offerKey.matches("[a-f0-9]{64}") || !"UNKNOWN".equals(row.path("availability").asText())
                    || !Set.of("card", "cash", "UNKNOWN").contains(row.path("payment").asText()) || !row.path("totalMismatch").isBoolean()) throw invalid();
                Long price = nullableMoney(row.path("price"), false, DataPolicy.MAX_AMOUNT);
                Long shipping = nullableMoney(row.path("shippingCost"), true, DataPolicy.MAX_AMOUNT);
                Long reported = nullableMoney(row.path("reportedTotal"), false, 2 * DataPolicy.MAX_AMOUNT);
                Long quoted = nullableMoney(row.path("quotedTotal"), false, 2 * DataPolicy.MAX_AMOUNT);
                Long sum = price != null && shipping != null ? price + shipping : null;
                boolean mismatch = sum != null && reported != null && !sum.equals(reported);
                if (row.path("totalMismatch").asBoolean() != mismatch || !Objects.equals(quoted, mismatch ? null : sum)) throw invalid();
                ObjectNode copy = row.deepCopy(); copy.remove("rowId");
                var old = unique.putIfAbsent(offerKey, copy);
                if (old != null && !old.equals(copy)) throw new ResponseStatusException(HttpStatus.CONFLICT, "동일 판매처 식별자의 관측이 충돌합니다");
                if (observed != null && quoted != null && "card".equals(row.path("payment").asText()))
                    samples.put(FixtureSnapshots.hash((observed + ":" + offerKey).getBytes(StandardCharsets.UTF_8)), quoted);
            }
            var content = new LinkedHashMap<String, Object>();
            content.put("schemaVersion", 1); content.put("source", "reef-danawa"); content.put("synthetic", false);
            content.put("productId", productId); content.put("optionKey", "danawa:" + productId);
            content.put("observedAt", observed == null ? null : observed.toString());
            content.put("page", result.path("page").asInt()); content.put("hasMore", result.path("hasMore").asBoolean());
            content.put("sellerOptionVerified", false); content.put("stockVerified", false);
            content.put("offers", mapper.convertValue(new ArrayList<>(unique.values()), new TypeReference<List<Map<String, Object>>>() {}));
            // receivedAt is part of identity only when upstream observation time is unavailable.
            if (observed == null) content.put("receivedAtWithoutObservation", received.toString());
            byte[] bytes = mapper.writeValueAsBytes(content);
            if (bytes.length > 256 * 1024) throw invalid();
            String id = FixtureSnapshots.hash((productId + ":" + result.path("page").asInt() + ":" + (observed == null ? "received:" + received : "observed:" + observed)).getBytes(StandardCharsets.UTF_8));
            String day = observed == null ? null : observed.atZone(DataPolicy.SEOUL).toLocalDate().toString();
            return new Prepared(id, FixtureSnapshots.hash(bytes), content, day, Map.copyOf(samples), observed, received,
                samples.values().stream().min(Long::compareTo).orElse(null));
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw invalid(); }
    }
    private static Long nullableMoney(JsonNode value, boolean zero, long max) {
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < (zero ? 0 : 1) || value.asLong() > max) throw invalid();
        return value.asLong();
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "다나와 관측의 출처·금액·시각·표본 한도를 확인하세요"); }
}
