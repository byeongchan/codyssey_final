package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.firestore.*;
import jakarta.annotation.PreDestroy;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable display cache and fenced job leases. Never stores price observations implicitly. */
@Service
public class LiveDataCache {
    public record View(String id, String kind, JsonNode data, String status, long expiresAt, String error) {}
    private record Claim(View view, String owner) {}
    private final FirebaseAccess firebase;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "live-data-collection"); thread.setDaemon(true); return thread;
    });
    private final Semaphore capacity = new Semaphore(32);
    public LiveDataCache(FirebaseAccess firebase, ObjectMapper mapper) { this.firebase = firebase; this.mapper = mapper; }
    public static String normalize(String query) { return query.strip().replaceAll("\\s+", " "); }
    public String key(String kind, Object input) {
        try { return FixtureSnapshots.hash(mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsBytes(Map.of("schema", "live-cache-v1", "kind", kind, "input", input))); }
        catch (Exception ex) { throw new IllegalArgumentException("잘못된 캐시 조건"); }
    }
    private <T> T await(com.google.api.core.ApiFuture<T> future) {
        try { return future.get(8, TimeUnit.SECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "공유 상품 저장소 요청이 중단되었습니다");
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firestore 응답 대기 시간이 초과되었습니다. 네트워크와 Firebase 연결을 확인하세요");
        } catch (ExecutionException ex) {
            return storageFailure(ex);
        }
    }
    private static <T> T storageFailure(Throwable failure) {
        String code = "UNKNOWN";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException status) throw status;
            if (cause instanceof com.google.api.gax.rpc.ApiException api)
                code = api.getStatusCode().getCode().name();
            if (cause instanceof io.grpc.StatusRuntimeException grpc) code = grpc.getStatus().getCode().name();
        }
        // Only log the status code: provider messages can contain project/credential details.
        org.slf4j.LoggerFactory.getLogger(LiveDataCache.class).warn("Firestore shared storage failed: status={}", code);
        String message = switch (code) {
            case "FAILED_PRECONDITION" -> "Firestore 조회 조건을 충족하지 못했습니다. 인덱스 배포·생성 완료와 기본 데이터베이스 설정을 확인하세요";
            case "NOT_FOUND" -> "Firestore 기본 데이터베이스를 찾지 못했습니다. 프로젝트 ID와 (default) 데이터베이스 생성을 확인하세요";
            case "PERMISSION_DENIED" -> "Firestore 접근 권한이 없습니다. Spring 서버 서비스 계정의 프로젝트·IAM 권한과 Firestore API 활성화를 확인하세요";
            case "UNAUTHENTICATED" -> "Firestore 서버 인증에 실패했습니다. GOOGLE_APPLICATION_CREDENTIALS와 서비스 계정 키 상태를 확인하세요";
            case "DEADLINE_EXCEEDED", "UNAVAILABLE" -> "Firestore에 연결하지 못했습니다. 네트워크·방화벽과 Firebase 서비스 상태를 확인하세요";
            case "RESOURCE_EXHAUSTED" -> "Firestore 할당량을 초과했습니다. 프로젝트 사용량과 할당량을 확인하세요";
            default -> "공유 상품 저장소 처리에 실패했습니다. Spring 서버의 Firestore 상태 로그를 확인하세요";
        };
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
    private DocumentReference ref(String id) { return firebase.firestore().collection("liveDisplayCache").document(id); }
    private long number(DocumentSnapshot doc, String field) { Long value = doc.getLong(field); return value == null ? 0 : value; }
    private View view(DocumentSnapshot doc, String id, String kind) {
        try {
            String json = doc.exists() ? doc.getString("payloadJson") : null;
            return new View(id, kind, json == null ? null : mapper.readTree(json), doc.exists() ? Objects.toString(doc.getString("status"), "MISSING") : "MISSING",
                doc.exists() ? number(doc, "expiresAt") : 0, doc.exists() ? doc.getString("errorCode") : null);
        } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 표시 데이터 형식을 확인하세요"); }
    }
    public View fetch(String kind, Map<String, Object> input, Duration ttl, Supplier<JsonNode> loader) {
        String id = key(kind, input), owner = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        var reference = ref(id);
        Claim claim = await(firebase.firestore().runTransaction(tx -> {
            var old = tx.get(reference).get(); var current = view(old, id, kind);
            var patch = new LinkedHashMap<String, Object>(); patch.put("kind", kind); patch.put("requestedAt", now); patch.put("requests", FieldValue.increment(1));
            patch.put("retireAt", com.google.cloud.Timestamp.of(java.util.Date.from(Instant.ofEpochMilli(now).plus(Duration.ofDays(30)))));
            if (current.data() != null && (current.expiresAt() > now || "explanation".equals(kind))) {
                patch.put("hits", FieldValue.increment(1)); tx.set(reference, patch, SetOptions.merge()); return new Claim(current, null);
            }
            if (old.exists() && (number(old, "leaseUntil") > now || number(old, "retryAt") > now)) {
                patch.put("merged", FieldValue.increment(1)); tx.set(reference, patch, SetOptions.merge()); return new Claim(current, null);
            }
            patch.put("owner", owner); patch.put("leaseUntil", now + 120_000); patch.put("retryAt", 0L);
            patch.put("status", "QUEUED"); patch.put("errorCode", null); patch.put("attempt", 1L);
            patch.put("inputJson", mapper.writeValueAsString(input)); patch.put("ttlMillis", ttl.toMillis());
            tx.set(reference, patch, SetOptions.merge());
            return new Claim(new View(id, kind, current.data(), "QUEUED", current.expiresAt(), null), owner);
        }));
        if (claim.owner() != null) {
            if (capacity.tryAcquire()) {
                try { workers.execute(() -> run(id, kind, owner, input, ttl, loader, 1)); }
                catch (RejectedExecutionException ex) { capacity.release(); fail(id, owner, "QUEUE_BUSY", 30_000); }
            } else fail(id, owner, "QUEUE_BUSY", 30_000);
        }
        return claim.view();
    }
    private void run(String id, String kind, String owner, Map<String, Object> input, Duration ttl, Supplier<JsonNode> loader, int attempt) {
        boolean retryScheduled = false;
        boolean fetched = false;
        try {
            boolean owns = await(firebase.firestore().runTransaction(tx -> {
                var reference = ref(id); var old = tx.get(reference).get();
                if (!owner.equals(old.getString("owner"))) return false;
                tx.set(reference, Map.of("status", "RUNNING", "attempt", (long)attempt, "leaseUntil", System.currentTimeMillis() + 120_000), SetOptions.merge()); return true;
            }));
            if (!owns) return;
            long started = System.nanoTime();
            JsonNode data = loader.get(); // External HTTP is outside every Firestore transaction.
            fetched = true;
            String json = mapper.writeValueAsString(data);
            if (json.getBytes(StandardCharsets.UTF_8).length > 700_000) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "수집 결과 크기 초과");
            await(firebase.firestore().runTransaction(tx -> {
                var reference = ref(id); var old = tx.get(reference).get(); long now = System.currentTimeMillis();
                if (!owner.equals(old.getString("owner")) || number(old, "leaseUntil") <= now) return false;
                var patch = new LinkedHashMap<String, Object>(); patch.put("payloadJson", json); patch.put("status", "SUCCEEDED");
                patch.put("expiresAt", now + ttl.toMillis()); patch.put("storedAt", now); patch.put("leaseUntil", 0L); patch.put("retryAt", 0L);
                patch.put("errorCode", null); patch.put("version", FixtureSnapshots.hash(json.getBytes(StandardCharsets.UTF_8)));
                patch.put("owner", null);
                patch.put("successes", FieldValue.increment(1)); patch.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                tx.set(reference, patch, SetOptions.merge());
                if (!"explanation".equals(kind)) catalogue(tx, kind, input, data, now);
                return true;
            }));
        } catch (Exception ex) {
            int status = ex instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
            if (!fetched && !"explanation".equals(kind) && (status == 429 || status == 503) && attempt < 3) {
                long delay = status == 429 ? 60_000 : 2000L << (attempt - 1);
                try {
                    boolean owns = await(firebase.firestore().runTransaction(tx -> {
                        var reference = ref(id); var old = tx.get(reference).get();
                        if (!owner.equals(old.getString("owner")) || number(old, "leaseUntil") <= System.currentTimeMillis()) return false;
                        long next = System.currentTimeMillis() + delay;
                        tx.set(reference, Map.of("status", "RETRY_WAIT", "errorCode", "HTTP_" + status, "retryAt", next, "leaseUntil", next + 120_000), SetOptions.merge()); return true;
                    }));
                    if (owns) { workers.schedule(() -> run(id, kind, owner, input, ttl, loader, attempt + 1), delay, TimeUnit.MILLISECONDS); retryScheduled = true; }
                } catch (RuntimeException ignored) { /* Lease expiration permits recovery on a later request. */ }
            } else {
                try { fail(id, owner, "HTTP_" + status, 60_000); } catch (RuntimeException ignored) { /* Recover after lease expiration. */ }
            }
        } finally { if (!retryScheduled) capacity.release(); }
    }
    private void fail(String id, String owner, String code, long cooldown) {
        await(firebase.firestore().runTransaction(tx -> {
            var reference = ref(id); var old = tx.get(reference).get();
            if (!owner.equals(old.getString("owner"))) return false;
            tx.set(reference, Map.of("status", "FAILED", "leaseUntil", 0L, "retryAt", System.currentTimeMillis() + cooldown,
                "errorCode", code, "failures", FieldValue.increment(1), "owner", FieldValue.delete()), SetOptions.merge()); return true;
        }));
    }
    private void catalogue(Transaction tx, String kind, Map<String, Object> input, JsonNode data, long now) throws Exception {
        if ("search".equals(kind)) for (JsonNode item : data.path("items")) {
            String productId = item.path("productId").asText();
            if (!productId.matches("[0-9]{1,30}")) continue;
            var patch = new LinkedHashMap<String, Object>(); patch.put("summaryJson", mapper.writeValueAsString(item));
            patch.put("updatedAtMillis", now); patch.put("receivedAt", data.path("receivedAt").asText());
            patch.put("observedAt", data.path("fetchedAt").isTextual() ? data.path("fetchedAt").asText() : null);
            patch.put("categoryIds", FieldValue.arrayUnion(Objects.toString(input.get("categoryId"), "")));
            if (item.path("image").isTextual()) patch.put("image", item.path("image").asText());
            tx.set(firebase.firestore().collection("liveDanawaCatalog").document(productId), patch, SetOptions.merge());
        }
        if ("product".equals(kind) && data.path("product").path("image").isTextual()) {
            tx.set(firebase.firestore().collection("liveDanawaCatalog").document(data.path("product").path("productId").asText()),
                Map.of("image", data.path("product").path("image").asText()), SetOptions.merge());
        }
    }
    public View job(String id, boolean explanation) {
        if (!id.matches("[a-f0-9]{64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업 ID 형식 오류");
        var doc = await(ref(id).get()); String kind = doc.getString("kind");
        if (!doc.exists() || kind == null || !Set.of("search", "product", "offers", "price_history", "explanation").contains(kind)
            || ("explanation".equals(kind) != explanation)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다");
        return view(doc, id, kind);
    }
    public JsonNode catalogue(String categoryId) {
        Query query = firebase.firestore().collection("liveDanawaCatalog");
        if (!categoryId.isBlank()) query = query.whereArrayContains("categoryIds", categoryId);
        var documents = await(query.orderBy("updatedAtMillis", Query.Direction.DESCENDING).limit(40).get()).getDocuments();
        var items = mapper.createArrayNode(); String received = null, observed = null;
        for (var doc : documents) {
            try {
                String json = doc.getString("summaryJson"); if (json == null) continue;
                ObjectNode item = (ObjectNode)mapper.readTree(json);
                if (doc.getString("image") != null) item.put("image", doc.getString("image"));
                item.put("displayUpdatedAt", doc.getString("observedAt")); item.put("displayReceivedAt", doc.getString("receivedAt"));
                items.add(item);
                if (received == null) { received = doc.getString("receivedAt"); observed = doc.getString("observedAt"); }
            } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 상품 카탈로그 형식 오류"); }
        }
        var data = mapper.createObjectNode(); data.put("source", "reef-danawa"); data.put("synthetic", false); data.put("action", "search");
        data.put("fetchedAt", observed); data.put("receivedAt", received == null ? Instant.now().toString() : received);
        data.put("localCacheHit", true); data.put("page", 1); data.put("hasMore", false); data.set("items", items);
        data.put("product", (String)null); data.put("offers", (String)null); data.put("savedCatalogue", true);
        return data;
    }
    private JsonNode images(JsonNode source) {
        if (!source.path("items").isArray() || source.path("items").isEmpty()) return source;
        try {
            var ids = new LinkedHashSet<String>();
            for (var item : source.path("items")) {
                ids.add(item.path("productId").asText());
                JsonNode cheapest = item;
                for (var variant : item.path("variants")) if (variant.path("listedPrice").isNumber()
                    && (!cheapest.path("listedPrice").isNumber() || variant.path("listedPrice").asLong() < cheapest.path("listedPrice").asLong())) cheapest = variant;
                ids.add(cheapest.path("productId").asText());
            }
            var refs = ids.stream().filter(id -> id.matches("[0-9]{1,30}")).limit(200)
                .map(id -> firebase.firestore().collection("liveDanawaCatalog").document(id)).toArray(DocumentReference[]::new);
            var known = new HashMap<String, String>();
            for (var doc : await(firebase.firestore().getAll(refs))) if (doc.getString("image") != null) known.put(doc.getId(), doc.getString("image"));
            JsonNode copy = source.deepCopy();
            for (var row : copy.path("items")) if (row.path("image").isNull() || row.path("image").isMissingNode()) {
                String image = known.get(row.path("productId").asText());
                if (image == null) {
                    JsonNode cheapest = row;
                    for (var variant : row.path("variants")) if (variant.path("listedPrice").isNumber()
                        && (!cheapest.path("listedPrice").isNumber() || variant.path("listedPrice").asLong() < cheapest.path("listedPrice").asLong())) cheapest = variant;
                    image = known.get(cheapest.path("productId").asText());
                }
                if (image != null) ((ObjectNode)row).put("image", image);
            }
            return copy;
        } catch (RuntimeException ex) { return source; }
    }
    public ResponseEntity<?> response(View view) {
        long now = System.currentTimeMillis(); boolean pending = Set.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(view.status());
        var meta = new LinkedHashMap<String, Object>(); meta.put("requestId", RequestIds.current()); meta.put("source", "explanation".equals(view.kind()) ? "gemini" : "reef-danawa");
        meta.put("synthetic", false); meta.put("referenceTime", Instant.now().toString()); meta.put("cacheHit", view.data() != null);
        meta.put("readSource", "firestore");
        meta.put("cacheFresh", view.data() != null && (view.expiresAt() > now || "explanation".equals(view.kind())));
        if (!"explanation".equals(view.kind())) meta.put("currency", "KRW");
        meta.put("cacheExpiresAt", view.expiresAt() == 0 ? null : Instant.ofEpochMilli(view.expiresAt()).toString());
        meta.put("collectionJobId", view.id()); meta.put("jobStatus", view.status()); meta.put("pollAfterSeconds", pending ? 2 : 0);
        meta.put("collectionState", pending ? "COLLECTING" : view.data() == null ? "FAILED" : "READY");
        String observed = view.data() == null ? null : view.data().path("fetchedAt").textValue(); meta.put("updatedAt", observed);
        long priceAge = "offers".equals(view.kind()) ? 300 : 900;
        boolean old = observed == null || Instant.parse(observed).isBefore(Instant.now().minusSeconds(priceAge));
        meta.put("stale", view.expiresAt() <= now || old); meta.put("errorCode", view.error());
        JsonNode payload = view.data();
        if ("search".equals(view.kind()) && payload != null && !payload.path("savedCatalogue").asBoolean()) payload = images(payload);
        if ("explanation".equals(view.kind()) && payload == null && !pending) {
            try {
                var input = mapper.readTree(await(ref(view.id()).get()).getString("inputJson"));
                var fallback = mapper.createObjectNode(); fallback.put("productId", input.path("productId").asText());
                fallback.put("evidenceSnapshotId", "danawa:" + input.path("productId").asText() + ":" + input.path("dataVersion").asText());
                fallback.put("model", "server-rule-only"); fallback.put("synthetic", false); fallback.put("explanationSource", "SERVER_FALLBACK");
                fallback.put("ruleVersion", PurchaseDecision.VERSION); fallback.put("explanationVersion", "explanation-v1"); fallback.put("generatedAt", Instant.now().toString());
                fallback.putArray("reasons").add("재고와 판매처 옵션이 검증되지 않아 서버가 구매 판단을 보류했습니다.");
                fallback.putArray("risks").add("Gemini 설명을 생성하지 못해 서버 기본 안내를 표시합니다.");
                payload = fallback; meta.put("source", "server-rule");
            } catch (Exception ignored) { /* Keep the standard collection error if storage is unavailable. */ }
        }
        var body = new LinkedHashMap<String, Object>(); body.put("data", payload); body.put("meta", meta);
        if (payload == null && !pending) body.put("error", Map.of("code", "COLLECTION_FAILED", "message", "수집을 완료하지 못했습니다. 잠시 후 다시 요청하세요", "requestId", RequestIds.current(), "retryable", true));
        return ResponseEntity.status(payload == null ? pending ? 202 : 503 : 200).header("Cache-Control", "no-store").header("Vary", "Authorization").body(body);
    }
    public Map<String, Object> metrics() {
        var rows = await(firebase.firestore().collection("liveDisplayCache").orderBy("requestedAt", Query.Direction.DESCENDING).limit(100).get()).getDocuments();
        var values = new LinkedHashMap<String, Object>(); values.put("sampleSize", rows.size()); values.put("scope", "최근 요청한 캐시 키 최대 100개");
        for (String field : List.of("requests", "hits", "merged", "successes", "failures")) values.put(field, rows.stream().mapToLong(row -> number(row, field)).sum());
        var kinds = new LinkedHashMap<String, Object>();
        for (String kind : List.of("search", "product", "offers", "price_history", "explanation")) {
            var selected = rows.stream().filter(row -> kind.equals(row.getString("kind"))).toList();
            var stats = new LinkedHashMap<String, Object>();
            for (String field : List.of("requests", "hits", "merged", "successes", "failures")) stats.put(field, selected.stream().mapToLong(row -> number(row, field)).sum());
            kinds.put(kind, stats);
        }
        values.put("byKind", kinds);
        values.put("actualCreditUsage", null); values.put("p95Verified", false); return values;
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
