package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me/danawa")
public class DanawaExplanationController {
    private final SessionVerifier sessions;
    private final DanawaObservations observations;
    private final AnalysisQuota quota;
    private final ObjectMapper mapper;
    private final String url, key, model, provider;
    private final Set<String> operators;
    private final LiveDataCache cache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public DanawaExplanationController(SessionVerifier sessions, DanawaObservations observations, AnalysisQuota quota, ObjectMapper mapper,
        String url, String key, String model, String provider, String uids) {
        this(sessions, observations, quota, mapper, url, key, model, provider, uids, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DanawaExplanationController(SessionVerifier sessions, DanawaObservations observations, AnalysisQuota quota, ObjectMapper mapper,
        @Value("${prizm.ai-url}") String url, @Value("${prizm.internal-key}") String key,
        @Value("${prizm.gemini-model:}") String model, @Value("${prizm.explanation-provider}") String provider,
        @Value("${prizm.operations-uids:}") String uids, LiveDataCache cache) {
        this.sessions=sessions; this.observations=observations; this.quota=quota; this.mapper=mapper;
        this.url=url; this.key=key; this.model=model; this.provider=provider; this.cache=cache;
        operators=new HashSet<>(); for (String uid:uids.split(",")) if (!uid.isBlank()) operators.add(uid.strip());
    }
    @PostMapping("/explanation/{productId}") public ResponseEntity<?> explain(@RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String productId) {
        var user=sessions.verify(token);
        if (!operators.contains(user.uid())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "분석 권한이 없습니다");
        if (!productId.matches("[0-9]{1,30}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID를 확인하세요");
        if (!"gemini".equals(provider) || !model.matches("gemini-[A-Za-z0-9._-]{1,100}")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER=gemini와 FastAPI와 동일한 GEMINI_MODEL을 설정하세요");
        var history=observations.history(productId);
        if (!Boolean.TRUE.equals(history.get("exists"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "먼저 실제 관측을 저장하세요");
        String version = Objects.toString(history.get("dataVersion"), "missing");
        String evidenceId = "danawa:" + productId + ":" + version;
        if (cache != null) {
            var input = Map.<String, Object>of("productId", productId, "optionKey", "danawa:" + productId, "dataVersion", version,
                "model", model, "ruleVersion", PurchaseDecision.VERSION, "explanationVersion", "explanation-v1");
            return cache.response(cache.fetch("explanation", input, Duration.ofDays(30), () -> {
                quota.consume(user.uid()); return generate(productId, history, evidenceId);
            }));
        }
        quota.consume(user.uid());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("data", generate(productId, history, evidenceId)));
    }
    @GetMapping("/jobs/{jobId}") public ResponseEntity<?> job(@RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String jobId) {
        var user = sessions.verify(token);
        if (!operators.contains(user.uid())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "분석 권한이 없습니다");
        return cache.response(cache.job(jobId, true));
    }
    /** User-provided texts stay request-local: never store them in the public shared cache. */
    @PostMapping("/review-summary/{productId}") public ResponseEntity<?> reviewSummary(
        @RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String productId, @RequestBody JsonNode input) {
        var user = sessions.verify(token);
        if (!productId.matches("[0-9]{1,30}") || !input.isObject() || input.size() != 1 || !input.path("reviews").isArray()
            || input.path("reviews").isEmpty() || input.path("reviews").size() > 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID와 리뷰 입력을 확인하세요");
        var texts = new LinkedHashSet<String>(); int length = 0;
        for (JsonNode review : input.path("reviews")) {
            if (!review.isTextual() || review.asText().isBlank() || review.asText().length() > 2000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리뷰는 각 2000자 이내로 입력하세요");
            String text = review.asText().strip();
            if (texts.add(text)) length += text.length();
        }
        if (length > 16000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리뷰 합계는 16000자 이내로 입력하세요");
        if (!"gemini".equals(provider) || !model.matches("gemini-[A-Za-z0-9._-]{1,100}"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini 모델 설정을 확인하세요");
        try {
            URI base = URI.create(url);
            if (!"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(Objects.toString(base.getHost(), ""))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !Set.of("", "/").contains(base.getPath()) || key.isBlank())
                throw new IllegalArgumentException();
            quota.consume(user.uid());
            var body = Map.of("productId", productId, "reviews", texts, "model", model);
            var request = java.net.http.HttpRequest.newBuilder(base.resolve("/internal/reviews/summarize")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Internal-Key", key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var stream = response.body()) { bytes = stream.readNBytes(65537); }
            if (response.statusCode() != 200 || bytes.length > 65536) throw new IllegalArgumentException();
            var result = mapper.readTree(bytes);
            if (!productId.equals(result.path("productId").asText()) || !model.equals(result.path("model").asText())
                || !"user-provided".equals(result.path("source").asText()) || !"review-summary-v1".equals(result.path("summaryVersion").asText())
                || !result.path("sampleCount").isIntegralNumber() || !result.path("sampleCount").canConvertToInt() || result.path("sampleCount").asInt() != texts.size()
                || !result.path("summary").isTextual() || result.path("summary").asText().isBlank() || result.path("summary").asText().length() > 400)
                throw new IllegalArgumentException();
            for (String field : List.of("positives", "negatives")) {
                var themes = result.path(field);
                if (!themes.isArray() || themes.size() > 3) throw new IllegalArgumentException();
                for (var theme : themes) {
                    if (!theme.path("theme").isTextual() || theme.path("theme").asText().isBlank() || theme.path("theme").asText().length() > 140
                        || !theme.path("reviewIds").isArray() || theme.path("reviewIds").isEmpty() || theme.path("reviewIds").size() > texts.size()) throw new IllegalArgumentException();
                    var seen = new HashSet<Integer>();
                    for (var id : theme.path("reviewIds")) if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() < 1 || id.asInt() > texts.size() || !seen.add(id.asInt()))
                        throw new IllegalArgumentException();
                }
            }
            return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(Map.of("data", result,
                "meta", Map.of("requestId", RequestIds.current(), "source", "gemini", "synthetic", false)));
        } catch (ResponseStatusException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "리뷰 요약 요청이 중단되었습니다"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "리뷰 요약을 완료하지 못했습니다. Gemini 연결·응답 설정을 확인하세요"); }
    }
    private JsonNode generate(String productId, Map<String, Object> history, String evidenceId) {
        try {
            URI target=URI.create(url);
            if (!"http".equals(target.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(target.getHost()) || target.getUserInfo()!=null || target.getQuery()!=null || target.getFragment()!=null)
                throw new IllegalArgumentException();
            JsonNode evidence=mapper.valueToTree(history);
            var body=mapper.createObjectNode();
            body.put("productId", productId); body.put("observedDays", evidence.path("analysis").path("quotedObservedDays").asInt());
            body.set("currentPrice", evidence.path("latestQuotedCardMinimum"));
            body.set("averagePrice", evidence.path("analysis").path("averageQuotedPrice"));
            body.put("referenceTime", Instant.now().toString()); body.put("verdict", "INSUFFICIENT_DATA");
            body.put("reasonCode", "UNVERIFIED_STOCK_OR_OPTION"); body.put("ruleVersion", PurchaseDecision.VERSION);
            body.put("explanationVersion", "explanation-v1"); body.put("evidenceSnapshotId", evidenceId);
            body.put("explanationProvider", "gemini"); body.put("explanationModel", model); body.put("synthetic", false);
            var request=java.net.http.HttpRequest.newBuilder(URI.create(url+"/internal/analyze")).timeout(Duration.ofSeconds(9))
                .header("Content-Type", "application/json").header("X-Internal-Key", key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var stream=response.body()) { bytes=stream.readNBytes(65537); }
            if (response.statusCode()!=200 || bytes.length>65536) throw new IllegalArgumentException();
            JsonNode result=mapper.readTree(bytes);
            if (!productId.equals(result.path("productId").asText()) || !result.path("synthetic").isBoolean() || result.path("synthetic").asBoolean()
                || !model.equals(result.path("model").asText()) || !body.path("evidenceSnapshotId").equals(result.path("evidenceSnapshotId"))
                || !body.path("ruleVersion").equals(result.path("ruleVersion")) || !body.path("explanationVersion").equals(result.path("explanationVersion"))
                || !result.path("reasons").isArray() || result.path("reasons").isEmpty() || !result.path("risks").isArray() || result.path("risks").isEmpty()) throw new IllegalArgumentException();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "분석이 중단되었습니다");
        } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini 연결·응답 설정을 확인하세요"); }
    }
}
