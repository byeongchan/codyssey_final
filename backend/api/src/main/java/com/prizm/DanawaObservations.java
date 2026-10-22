package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Live public price records have their own root; no fixture or user collections are written. */
@Service
public class DanawaObservations {
    private final FirebaseAccess firebase;
    private final ObjectMapper mapper;
    public DanawaObservations(FirebaseAccess firebase, ObjectMapper mapper) { this.firebase = firebase; this.mapper = mapper; }
    public Map<String, Object> save(JsonNode result) {
        var prepared = DanawaObservationPolicy.prepare(mapper, result, Instant.now());
        String productId = (String)prepared.content().get("productId");
        var root = firebase.firestore().collection("liveDanawaProducts").document(productId);
        var reference = root.collection("danawaObservations").document(prepared.id());
        return PersonalData.await(firebase.firestore().runTransaction(tx -> {
            var old = tx.get(reference).get();
            if (old.exists()) {
                if (!prepared.digest().equals(old.getString("contentDigest"))) throw conflict();
                return receipt(prepared, true);
            }
            var product = tx.get(root).get();
            DocumentReference dayRef = prepared.day() == null ? null : root.collection("danawaDailyQuotes").document(prepared.day());
            if (product.exists() && (!"reef-danawa".equals(product.getString("source")) || !Boolean.FALSE.equals(product.getBoolean("synthetic"))
                || !("danawa:" + productId).equals(product.getString("optionKey")))) throw conflict();
            DocumentSnapshot day = dayRef == null ? null : tx.get(dayRef).get();
            var samples = new TreeMap<String, Long>();
            long observationCount = 0;
            if (day != null && day.exists()) {
                if (!"reef-danawa".equals(day.getString("source")) || !Boolean.FALSE.equals(day.getBoolean("synthetic"))) throw conflict();
                if (!(day.get("samples") instanceof Map<?, ?> saved)) throw conflict();
                for (var entry : saved.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !key.matches("[a-f0-9]{64}") || !(entry.getValue() instanceof Number value)
                        || !(value instanceof Long || value instanceof Integer)
                        || value.longValue() < 1 || value.longValue() > 2 * DataPolicy.MAX_AMOUNT) throw conflict();
                    samples.put(key, value.longValue());
                }
                Long count = day.getLong("observationCount"); if (count == null || count < 0 || count >= 10000) throw conflict();
                observationCount = count;
            }
            for (var entry : prepared.samples().entrySet()) {
                var previous = samples.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) throw conflict();
            }
            if (samples.size() > 2000) throw new ResponseStatusException(HttpStatus.CONFLICT, "상품별 일일 카드 표시가 표본 2000개 한도를 초과했습니다");
            var observation = new LinkedHashMap<>(prepared.content());
            observation.put("contentDigest", prepared.digest()); observation.put("receivedAt", prepared.receivedAt().toString());
            observation.put("createdAt", FieldValue.serverTimestamp()); observation.put("quotedCardMinimum", prepared.minimum());
            tx.create(reference, observation);
            if (dayRef != null) {
                var aggregate = new LinkedHashMap<String, Object>();
                aggregate.put("schemaVersion", 1); aggregate.put("source", "reef-danawa"); aggregate.put("synthetic", false); aggregate.put("date", prepared.day());
                aggregate.put("samples", samples); aggregate.put("quotedCardMinimum", samples.values().stream().min(Long::compareTo).orElse(null));
                aggregate.put("validQuoteSamples", samples.size()); aggregate.put("observationCount", observationCount + 1);
                aggregate.put("decisionEligibleSamples", 0); aggregate.put("updatedAt", FieldValue.serverTimestamp());
                tx.set(dayRef, aggregate);
            }
            var mapping = new LinkedHashMap<String, Object>();
            mapping.put("schemaVersion", 1); mapping.put("source", "reef-danawa"); mapping.put("synthetic", false); mapping.put("providerProductId", productId);
            mapping.put("optionKey", "danawa:" + productId); mapping.put("identityBasis", "DANAWA_PRODUCT_ID");
            mapping.put("sellerOptionVerified", false); mapping.put("stockVerified", false); mapping.put("lastStoredAt", FieldValue.serverTimestamp());
            String oldTime = product.exists() ? product.getString("latestObservedAt") : null;
            String oldId = product.exists() ? product.getString("latestObservationId") : null;
            String oldReceived = product.exists() ? product.getString("latestReceivedAt") : null;
            boolean replace = !product.exists() || (prepared.observedAt() != null && (oldTime == null
                || prepared.observedAt().isAfter(Instant.parse(oldTime)) || (prepared.observedAt().equals(Instant.parse(oldTime)) && prepared.id().compareTo(Objects.toString(oldId, "")) > 0)))
                || (prepared.observedAt() == null && oldTime == null && (oldReceived == null || prepared.receivedAt().isAfter(Instant.parse(oldReceived))));
            if (replace) {
                mapping.put("latestObservationId", prepared.id()); mapping.put("latestObservedAt", prepared.observedAt() == null ? null : prepared.observedAt().toString());
                mapping.put("latestReceivedAt", prepared.receivedAt().toString()); mapping.put("latestQuotedCardMinimum", prepared.minimum());
                mapping.put("latestPage", prepared.content().get("page")); mapping.put("latestHasMore", prepared.content().get("hasMore"));
            }
            tx.set(root, mapping, SetOptions.merge());
            return receipt(prepared, false);
        }));
    }
    private Map<String, Object> receipt(DanawaObservationPolicy.Prepared prepared, boolean duplicate) {
        var value = new LinkedHashMap<String, Object>();
        value.put("observationId", prepared.id()); value.put("duplicate", duplicate); value.put("productId", prepared.content().get("productId"));
        value.put("observedAt", prepared.observedAt() == null ? null : prepared.observedAt().toString()); value.put("day", prepared.day());
        value.put("validQuoteSamples", prepared.samples().size()); value.put("decisionEligible", false); return value;
    }
    public Map<String, Object> history(String productId) {
        var root = firebase.firestore().collection("liveDanawaProducts").document(productId);
        return PersonalData.await(firebase.firestore().runTransaction(tx -> {
            var product = tx.get(root).get();
            if (product.exists() && (!"reef-danawa".equals(product.getString("source")) || !Boolean.FALSE.equals(product.getBoolean("synthetic"))
                || !("danawa:" + productId).equals(product.getString("optionKey")))) throw conflict();
            var rows = tx.get(root.collection("danawaDailyQuotes").orderBy(FieldPath.documentId(), Query.Direction.DESCENDING).limit(90)).get().getDocuments();
            var points = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                LocalDate date = LocalDate.parse(row.getId());
                Long minimum = row.getLong("quotedCardMinimum"), count = row.getLong("validQuoteSamples"), observations = row.getLong("observationCount");
                if (!"reef-danawa".equals(row.getString("source")) || !Boolean.FALSE.equals(row.getBoolean("synthetic"))
                    || date.isAfter(LocalDate.now(DataPolicy.SEOUL)) || count == null || count < 0 || count > 2000
                    || observations == null || observations < 1 || observations > 10000
                    || (minimum == null ? count != 0 : minimum < 1 || minimum > 2 * DataPolicy.MAX_AMOUNT || count == 0)) throw conflict();
                var point = new LinkedHashMap<String, Object>(); point.put("date", row.getId());
                point.put("quotedCardMinimum", minimum); point.put("validQuoteSamples", count);
                point.put("observationCount", observations); point.put("decisionEligibleSamples", 0); points.add(point);
            }
            Collections.reverse(points);
            String start = LocalDate.now(DataPolicy.SEOUL).minusDays(29).toString();
            var recent = points.stream().filter(p -> ((String)p.get("date")).compareTo(start) >= 0 && p.get("quotedCardMinimum") instanceof Number).toList();
            double average = recent.stream().mapToLong(p -> ((Number)p.get("quotedCardMinimum")).longValue()).average().orElse(0);
            var analysis = new LinkedHashMap<String, Object>(Map.of("verdict", "INSUFFICIENT_DATA", "ruleVersion", PurchaseDecision.VERSION,
                "reasonCode", "UNVERIFIED_STOCK_OR_OPTION", "reasons", List.of("판매처 재고와 옵션 일치가 확인되지 않아 구매 판단을 보류합니다."),
                "eligibleObservedDays", 0, "quotedObservedDays", recent.size(), "averageQuotedPrice", average,
                "explanationSource", "SERVER_RULE_ONLY"));
            analysis.put("averageQuotedPrice", recent.isEmpty() ? null : average);
            var data = new LinkedHashMap<String, Object>();
            data.put("productId", productId); data.put("optionKey", "danawa:" + productId); data.put("exists", product.exists()); data.put("points", points);
            data.put("latestObservedAt", product.exists() ? product.getString("latestObservedAt") : null);
            data.put("latestQuotedCardMinimum", product.exists() ? product.getLong("latestQuotedCardMinimum") : null);
            data.put("analysis", analysis); data.put("source", "reef-danawa"); data.put("synthetic", false);
            data.put("currency", "KRW");
            var observed = points.stream().filter(p -> p.get("quotedCardMinimum") != null).toList();
            data.put("observedDays", observed.size()); data.put("coverageStart", observed.isEmpty() ? null : observed.get(0).get("date"));
            data.put("coverageEnd", observed.isEmpty() ? null : observed.get(observed.size() - 1).get("date"));
            data.put("dataVersion", FixtureSnapshots.hash(mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(data)));
            return data;
        }));
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "기존 다나와 관측·집계 근거가 충돌합니다"); }
}
