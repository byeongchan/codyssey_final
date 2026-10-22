package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final CatalogRepository repo;
    private final ObjectMapper mapper;
    private final String internalKey;
    private final String aiUrl;
    private final AnalysisJobs analysisJobs;
    private final ProductCache productCache;
    private final SessionVerifier sessions;
    private final AnalysisQuota quota;
    @Value("${prizm.explanation-provider:fixture}") private String explanationProvider = "fixture";
    @Value("${prizm.gemini-model:}") private String geminiModel = "";
    @Value("${prizm.provider:fixture}") private String dataProvider = "fixture";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Object> batches = new ConcurrentHashMap<>();
    @jakarta.annotation.PostConstruct public void validateExplanationConfiguration() {
        if ("reef-danawa".equals(dataProvider)) return;
        if (!Set.of("fixture", "gemini").contains(explanationProvider)
            || ("gemini".equals(explanationProvider) && !geminiModel.matches("gemini-[A-Za-z0-9._-]{1,100}")))
            throw new IllegalArgumentException("AI_PROVIDER와 GEMINI_MODEL 설명 설정을 확인하세요");
    }
    private String explanationModel() { return "gemini".equals(explanationProvider) ? geminiModel : "fixture-no-gemini-call"; }

    public ApiController(CatalogRepository repo, ObjectMapper mapper,
            @Value("${prizm.internal-key}") String internalKey, @Value("${prizm.ai-url}") String aiUrl, AnalysisJobs analysisJobs, ProductCache productCache, SessionVerifier sessions, AnalysisQuota quota) {
        this.repo = repo; this.mapper = mapper; this.internalKey = internalKey; this.aiUrl = aiUrl; this.analysisJobs = analysisJobs; this.productCache = productCache;
        this.sessions = sessions; this.quota = quota;
    }
    private Map<String, Object> meta() {
        return Map.of("source", "fixture", "synthetic", true, "referenceTime", repo.referenceTime(),
                "updatedAt", repo.referenceTime(), "stale", false, "requestId", RequestIds.current(), "catalogVersion", Objects.toString(repo.dataVersion(), ""));
    }
    private Map<String, Object> single(Object data) { return Map.of("data", data, "meta", meta()); }
    private Map<String, Object> single(Object data, boolean stale, String updatedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>(meta());
        metadata.put("stale", stale); metadata.put("updatedAt", updatedAt);
        return Map.of("data", data, "meta", metadata);
    }
    private Instant referenceTime() { return Instant.parse(repo.referenceTime()); }
    private Map<String, Object> listing(List<?> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items); result.put("nextCursor", null); result.put("meta", meta()); return result;
    }
    private JsonNode product(String id) {
        return repo.products().stream().filter(p -> id.equals(p.path("id").asText())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다"));
    }
    private List<JsonNode> offers(String id) {
        return repo.offers(id).stream().map(raw -> {
            ObjectNode offer = raw.deepCopy();
            boolean stale = DataPolicy.stale(Instant.parse(offer.path("observedAt").asText()), referenceTime());
            offer.put("stale", stale);
            offer.put("expiresAt", Instant.parse(offer.path("observedAt").asText()).plus(DataPolicy.MAX_AGE).toString());
            if (offer.path("shippingFee").isNumber() && "IN_STOCK".equals(offer.path("availability").asText())) {
                offer.put("comparablePrice", offer.path("productPrice").asLong() + offer.path("shippingFee").asLong()
                        - offer.path("unconditionalDiscount").asLong());
            } else offer.putNull("comparablePrice");
            return (JsonNode) offer;
        }).sorted(Comparator.comparingLong(o -> o.path("comparablePrice").isNumber()
                ? o.path("comparablePrice").asLong() : Long.MAX_VALUE)).toList();
    }
    private JsonNode summary(JsonNode raw) {
        return cachedSummary(raw).data();
    }
    private ProductCache.Result cachedSummary(JsonNode raw) {
        String key = repo.dataVersion() + ":" + repo.referenceTime() + ":" + raw.path("id").asText();
        return productCache.get(key, () -> loadSummary(raw));
    }
    private JsonNode loadSummary(JsonNode raw) {
        ObjectNode result = raw.deepCopy();
        List<JsonNode> valid = offers(raw.path("id").asText()).stream().filter(o -> o.path("comparablePrice").isNumber()).toList();
        valid.stream().filter(o -> !o.path("stale").asBoolean()).findFirst()
                .ifPresentOrElse(o -> result.set("currentPrice", o.get("comparablePrice")), () -> result.putNull("currentPrice"));
        result.put("priceStale", !valid.isEmpty() && valid.stream().allMatch(o -> o.path("stale").asBoolean()));
        valid.stream().findFirst().ifPresentOrElse(o -> result.set("lastKnownPrice", o.get("comparablePrice")), () -> result.putNull("lastKnownPrice"));
        repo.offers(raw.path("id").asText()).stream().map(o -> o.path("observedAt").asText())
                .max(Comparator.comparing(Instant::parse)).ifPresentOrElse(time -> result.put("updatedAt", time), () -> result.putNull("updatedAt"));
        return result;
    }
    @GetMapping("/health") public Object health() { return Map.of("data", Map.of("status", "UP", "mode", dataProvider), "meta", Map.of("source", dataProvider, "synthetic", "fixture".equals(dataProvider), "requestId", RequestIds.current(), "referenceTime", Instant.now().toString(), "stale", false)); }
    @GetMapping("/categories") public Object categories() { return listing(repo.categories()); }
    @GetMapping("/products") public Object products(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String categoryId, @RequestParam(defaultValue="title") String sort,
            @RequestParam(defaultValue="0") int cursor, @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="normal") String scenario) {
        if (limit < 1 || limit > 50 || cursor < 0 || !List.of("title", "priceAsc").contains(sort)
                || !List.of("normal", "empty", "error").contains(scenario))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 조건이 올바르지 않습니다");
        if ("error".equals(scenario)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "더미 공급자 장애");
        List<JsonNode> found = "empty".equals(scenario) ? List.of() : repo.products().stream()
                .filter(p -> p.path("title").asText().toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT)))
                .filter(p -> categoryId.isBlank() || categoryId.equals(p.path("categoryId").asText())).map(this::summary)
                .sorted("priceAsc".equals(sort) ? Comparator.<JsonNode>comparingLong(p -> p.path("currentPrice").isNumber()
                        ? p.path("currentPrice").asLong() : Long.MAX_VALUE) : Comparator.<JsonNode, String>comparing(p -> p.path("title").asText()))
                .toList();
        if (cursor > found.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서");
        int end = Math.min(cursor + limit, found.size());
        Map<String, Object> result = listing(found.subList(cursor, end));
        result.put("nextCursor", end < found.size() ? Integer.toString(end) : null);
        Map<String, Object> metadata = new LinkedHashMap<>(meta());
        metadata.put("stale", found.subList(cursor, end).stream().anyMatch(p -> p.path("priceStale").asBoolean()));
        metadata.put("updatedAt", found.subList(cursor, end).stream().filter(p -> p.path("updatedAt").isTextual())
                .map(p -> p.path("updatedAt").asText()).max(Comparator.comparing(Instant::parse)).orElse(null));
        result.put("meta", metadata); return result;
    }
    @GetMapping("/products/{id}") public Object detail(@PathVariable String id) {
        var cached = cachedSummary(product(id)); JsonNode p = cached.data();
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Product-Cache", cached.hit() ? "HIT" : "MISS")
            .body(single(p, p.path("priceStale").asBoolean(), p.path("updatedAt").isTextual() ? p.path("updatedAt").asText() : null));
    }
    @GetMapping("/products/{id}/offers") public Object getOffers(@PathVariable String id) {
        product(id); List<JsonNode> items = offers(id); Map<String, Object> result = listing(items);
        Map<String, Object> metadata = new LinkedHashMap<>(meta());
        metadata.put("stale", items.stream().anyMatch(o -> o.path("stale").asBoolean()));
        metadata.put("updatedAt", items.stream().map(o -> o.path("observedAt").asText()).max(Comparator.comparing(Instant::parse)).orElse(null));
        result.put("meta", metadata); return result;
    }
    private Map<String, Object> priceData(String id, int days) {
        product(id);
        if (days < 1 || days > 90) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days는 1~90입니다");
        LocalDate last = Instant.parse(repo.referenceTime()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        List<JsonNode> points = repo.prices(id).stream()
                .filter(p -> !LocalDate.parse(p.path("date").asText()).isBefore(last.minusDays(days - 1)))
                .filter(p -> !LocalDate.parse(p.path("date").asText()).isAfter(last))
                .sorted(Comparator.comparing(p -> p.path("date").asText())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("points", points); data.put("observedDays", points.size()); data.put("currency", "KRW");
        data.put("coverageStart", points.isEmpty() ? null : points.get(0).path("date").asText());
        data.put("coverageEnd", points.isEmpty() ? null : points.get(points.size()-1).path("date").asText());
        data.put("averagePrice", points.stream().mapToLong(p -> p.path("comparablePrice").asLong()).average().orElse(0));
        data.put("stale", points.isEmpty() || DataPolicy.historyStale(points.get(points.size()-1).path("date").asText(), referenceTime()));
        if (points.isEmpty()) data.put("updatedAt", null);
        else {
            Instant dayEnd = LocalDate.parse(points.get(points.size()-1).path("date").asText())
                    .plusDays(1).atStartOfDay(DataPolicy.SEOUL).toInstant().minusNanos(1);
            data.put("updatedAt", (dayEnd.isAfter(referenceTime()) ? referenceTime() : dayEnd).toString());
        }
        return data;
    }
    @GetMapping("/products/{id}/prices") public Object prices(@PathVariable String id, @RequestParam(defaultValue="90") int days) {
        Map<String, Object> data = priceData(id, days);
        return single(data, Boolean.TRUE.equals(data.get("stale")), (String) data.get("updatedAt"));
    }
    private String analysisKey(String id) {
        ObjectNode input = mapper.createObjectNode();
        input.set("product", product(id));
        input.set("offers", mapper.valueToTree(repo.offers(id).stream().sorted(Comparator.comparing(o -> o.path("id").asText())).toList()));
        input.set("prices", mapper.valueToTree(repo.prices(id).stream().sorted(Comparator.comparing(o -> o.path("date").asText())).toList()));
        input.put("referenceTime", repo.referenceTime());
        input.put("catalogVersion", repo.dataVersion());
        input.put("model", explanationModel()); input.put("explanationProvider", explanationProvider); input.put("analysisVersion", "fixture-v3"); input.put("ruleVersion", PurchaseDecision.VERSION); input.put("explanationVersion", AnalysisExplanation.VERSION);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(input)));
        } catch (Exception ex) { throw new IllegalStateException("분석 입력 버전을 만들지 못했습니다", ex); }
    }
    @GetMapping("/products/{id}/analysis") public ResponseEntity<?> analysis(@PathVariable String id, @RequestHeader(value="Authorization", defaultValue="") String authorization) {
        var user = sessions.verify(authorization);
        JsonNode cached = analysisJobs.cached(user.uid(), analysisKey(id), referenceTime());
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization")
                .body(single(cached, cached.path("stale").asBoolean(), cached.path("generatedAt").asText()));
    }
    @PostMapping("/products/{id}/analysis-jobs") public ResponseEntity<?> startAnalysis(@PathVariable String id, @RequestHeader(value="Authorization", defaultValue="") String authorization) {
        var user = sessions.verify(authorization);
        String key = analysisKey(id);
        quota.consume(user.uid());
        Map<String, Object> job = analysisJobs.submit(user.uid(), id, key, referenceTime(), () -> CatalogViews.read(repo, () -> {
            try {
                JsonNode result = mapper.valueToTree(computeAnalysis(id).get("data"));
                if (!key.equals(analysisKey(id))) throw new IllegalStateException("분석 중 근거 데이터가 변경되었습니다");
                return result;
            } catch (Exception ex) { throw new IllegalStateException("분석 작업 실패", ex); }
        }));
        return ResponseEntity.accepted().header("Location", "/api/v1/analysis-jobs/" + job.get("jobId"))
                .header("Retry-After", "1").header("Cache-Control", "no-store").header("Vary", "Authorization").body(single(job));
    }
    @GetMapping("/analysis-jobs/{jobId}") public ResponseEntity<?> analysisJob(@PathVariable String jobId, @RequestHeader(value="Authorization", defaultValue="") String authorization) {
        var user = sessions.verify(authorization);
        Map<String, Object> job = analysisJobs.get(user.uid(), jobId, referenceTime());
        if (!analysisKey((String) job.get("productId")).equals(job.get("dataVersion"))) AnalysisJobs.expired(job);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(single(job));
    }
    private Map<String, Object> computeAnalysis(String id) throws Exception {
        JsonNode p = summary(product(id));
        Map<String, Object> stats = priceData(id, 30);
        boolean stale = p.path("priceStale").asBoolean() || Boolean.TRUE.equals(stats.get("stale"));
        Long currentPrice = p.path("currentPrice").isNumber() ? p.path("currentPrice").asLong() : null;
        int days = (int) stats.get("observedDays");
        double average = ((Number) stats.get("averagePrice")).doubleValue();
        var decision = PurchaseDecision.decide(currentPrice, average, days, stale);
        String evidenceId = "fixture:" + analysisKey(id);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("productId", id); input.put("observedDays", days); input.put("averagePrice", average);
        input.put("currentPrice", currentPrice); input.put("referenceTime", repo.referenceTime());
        input.put("verdict", decision.verdict()); input.put("reasonCode", decision.reasonCode());
        input.put("ruleVersion", PurchaseDecision.VERSION); input.put("explanationVersion", AnalysisExplanation.VERSION);
        input.put("evidenceSnapshotId", evidenceId);
        input.put("explanationProvider", explanationProvider); input.put("explanationModel", explanationModel());
        var explanation = "INSUFFICIENT_DATA".equals(decision.verdict())
            ? AnalysisExplanation.fallback(decision, "SERVER_RULE_ONLY")
            : AnalysisExplanation.resolve(input, decision, () -> {
                try {
                    if (internalKey.isBlank()) throw new IllegalStateException("Missing internal key");
                    HttpRequest request = HttpRequest.newBuilder(URI.create(aiUrl + "/internal/analyze"))
                        .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("X-Internal-Key", internalKey)
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || response.body().length() > 16000) throw new IllegalStateException("Invalid explanation response");
                    return mapper.readTree(response.body());
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            });
        Instant expiresAt = referenceTime().plus(DataPolicy.MAX_AGE);
        if (stale) expiresAt = referenceTime();
        else for (JsonNode offer : offers(id)) {
            if (offer.path("comparablePrice").isNumber() && !offer.path("stale").asBoolean()) {
                Instant priceExpiry = Instant.parse(offer.path("expiresAt").asText());
                if (priceExpiry.isBefore(expiresAt)) expiresAt = priceExpiry;
                break;
            }
        }
        ObjectNode output = mapper.createObjectNode();
        output.put("productId", id).put("verdict", decision.verdict()).put("reasonCode", decision.reasonCode());
        output.put("ruleVersion", PurchaseDecision.VERSION).put("explanationVersion", AnalysisExplanation.VERSION);
        output.put("analysisVersion", "fixture-v3").put("model", explanation.model()).put("synthetic", true);
        output.put("observedDays", days).put("currentPrice", currentPrice).put("averagePrice", average);
        output.put("generatedAt", repo.referenceTime()).put("expiresAt", expiresAt.toString()).put("stale", stale);
        output.put("evidenceSnapshotId", evidenceId).put("explanationSource", explanation.source());
        output.set("reasons", mapper.valueToTree(explanation.reasons())); output.set("risks", mapper.valueToTree(explanation.risks()));
        return single(output, stale, repo.referenceTime());
    }
    /** 현재는 fixture 수집 이벤트만 수신한다. 실제 가격 쓰기는 실연동 단계에서 추가한다. */
    @PostMapping("/internal/fixture-batches") public Object batch(@RequestHeader(value="X-Internal-Key", defaultValue="") String key,
            @RequestBody JsonNode body) {
        if (internalKey.isBlank() || !java.security.MessageDigest.isEqual(internalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "내부 인증 실패");
        if (body == null || !body.isObject() || !body.path("idempotencyKey").isTextual()
                || !body.path("referenceTime").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 fixture 이벤트 형식");
        String id = body.path("idempotencyKey").asText();
        if (id.isBlank() || id.length() > 200 || !"fixture".equals(body.path("source").asText())
                || !repo.referenceTime().equals(body.path("referenceTime").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 fixture 이벤트");
        if (!batches.containsKey(id) && batches.size() >= 1000)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "더미 이벤트 저장 한도");
        Object previous = batches.putIfAbsent(id, Map.of("idempotencyKey", id, "accepted", true));
        return single(Map.of("idempotencyKey", id, "duplicate", previous != null, "accepted", true));
    }
}
