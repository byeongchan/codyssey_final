package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only stored evidence. Never fetches more listings, news, specs or images. */
@RestController
@RequestMapping(value = "/api/v1/me/used/agent", produces = "application/json")
public class UsedAgentController {
  private final SessionVerifier sessions;
  private final FirebaseAccess firebase;
  private final LiveDataCache cache;
  private final ObjectMapper mapper;
  private final AnalysisQuota quota;
  private final String aiUrl, key, model;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public UsedAgentController(
      SessionVerifier sessions,
      FirebaseAccess firebase,
      LiveDataCache cache,
      ObjectMapper mapper,
      AnalysisQuota quota,
      @Value("${prizm.ai-url}") String aiUrl,
      @Value("${prizm.internal-key}") String key,
      @Value("${prizm.gemini-model:}") String model) {
    this.sessions = sessions;
    this.firebase = firebase;
    this.cache = cache;
    this.mapper = mapper;
    this.quota = quota;
    this.aiUrl = aiUrl;
    this.key = key;
    this.model = model;
  }

  private ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private JsonNode snapshot(String id, String kind) {
    if (!id.matches("[a-f0-9]{64}")) throw invalid("조회 근거를 확인하세요");
    var doc =
        PersonalData.await(
            firebase.firestore().collection("liveDisplayCache").document(id).get(), "중고 상담 근거 읽기");
    if (!doc.exists()
        || !kind.equals(doc.getString("kind"))
        || doc.getString("payloadJson") == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "매물 목록과 상세를 다시 조회해 주세요");
    try {
      var data = mapper.readTree(doc.getString("payloadJson"));
      String provider = kind.contains("karrot") ? "karrot" : "joongna";
      if (!("reef-" + provider).equals(data.path("source").asText())
          || !data.path("synthetic").isBoolean()
          || data.path("synthetic").asBoolean()
          || !provider.equals(data.path("provider").asText())) throw new IllegalArgumentException();
      if (kind.endsWith("-search") && data.isObject() && doc.getString("inputJson") != null) {
        var input = mapper.readTree(doc.getString("inputJson"));
        ((ObjectNode) data).set("query", input.path("query"));
      }
      return data;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 중고 근거를 확인하지 못했습니다");
    }
  }

  private JsonNode generate(Object facts, String version, String question, JsonNode history)
      throws Exception {
    JsonNode modelFacts = mapper.valueToTree(facts);
    // Full counts stay intact; limit only examples so region statistics and description fit.
    for (JsonNode analysis :
        List.of(
            modelFacts.path("assessment"), modelFacts.path("assessment").path("searchAnalysis"))) {
      if (analysis.isObject() && analysis.path("excludedListings").isArray()) {
        var preview = mapper.createArrayNode();
        for (int i = 0; i < Math.min(10, analysis.path("excludedListings").size()); i++)
          preview.add(analysis.path("excludedListings").get(i));
        ((ObjectNode) analysis).set("excludedListings", preview);
      }
    }
    var uri = URI.create(aiUrl);
    if (!"http".equals(uri.getScheme())
        || !Set.of("localhost", "127.0.0.1").contains(Objects.toString(uri.getHost(), ""))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !Set.of("", "/").contains(uri.getPath())
        || key.isBlank()) throw new IllegalArgumentException();
    var request =
        java.net.http.HttpRequest.newBuilder(uri.resolve("/internal/used-agent/analyze"))
            .timeout(Duration.ofSeconds(150))
            .header("Content-Type", "application/json")
            .header("X-Internal-Key", key)
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        Map.of(
                            "evidence",
                            modelFacts,
                            "evidenceVersion",
                            version,
                            "question",
                            question,
                            "history",
                            history,
                            "model",
                            model))))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    byte[] bytes;
    try (var stream = response.body()) {
      bytes = stream.readNBytes(65537);
    }
    if (response.statusCode() != 200 || bytes.length > 65536) throw new IllegalArgumentException();
    var generated = mapper.readTree(bytes);
    if (!version.equals(generated.path("evidenceVersion").asText())
        || !model.equals(generated.path("model").asText())) throw new IllegalArgumentException();
    return generated;
  }

  private List<JsonNode> withStoredDetails(List<JsonNode> rows, JsonNode selected) {
    // Never collect new listings here. Read a bounded set of already viewed details.
    var ordered = new ArrayList<>(rows);
    ordered.sort(
        Comparator.comparingInt(
            row -> UsedAgentPolicy.title(row).equals(UsedAgentPolicy.title(selected)) ? 0 : 1));
    var uniqueTargets = new LinkedHashMap<String, JsonNode>();
    for (var row : ordered)
      uniqueTargets.putIfAbsent(
          row.path("provider").asText() + ":" + row.path("productId").asText(), row);
    var targets = uniqueTargets.values().stream().limit(60).toList();
    if (targets.isEmpty()) return rows;
    var refs =
        targets.stream()
            .map(
                row ->
                    firebase
                        .firestore()
                        .collection("liveDisplayCache")
                        .document(
                            cache.key(
                                "used-" + row.path("provider").asText() + "-detail",
                                Map.of("productId", row.path("productId").asText()))))
            .toArray(com.google.cloud.firestore.DocumentReference[]::new);
    var docs = PersonalData.await(firebase.firestore().getAll(refs), "중고 상세 비교 근거 읽기");
    var enriched = new HashMap<String, JsonNode>();
    for (int i = 0; i < docs.size(); i++) {
      var doc = docs.get(i);
      var row = targets.get(i);
      String provider = row.path("provider").asText(), id = row.path("productId").asText();
      if (!doc.exists()
          || !("used-" + provider + "-detail").equals(doc.getString("kind"))
          || doc.getString("payloadJson") == null) continue;
      try {
        var data = mapper.readTree(doc.getString("payloadJson"));
        var product = data.path("product");
        if (!("reef-" + provider).equals(data.path("source").asText())
            || !data.path("synthetic").isBoolean()
            || data.path("synthetic").asBoolean()
            || !provider.equals(data.path("provider").asText())
            || !id.equals(product.path("productId").asText())
            || !provider.equals(product.path("provider").asText())
            || !product.isObject()
            || !UsedAgentPolicy.fresh(data)) continue;
        ObjectNode copy = product.deepCopy();
        copy.set("observedAt", data.path("fetchedAt"));
        enriched.put(provider + ":" + id, copy);
      } catch (Exception ignored) {
        /* Missing/unusable detail remains explicitly unconfirmed. */
      }
    }
    return rows.stream()
        .map(
            row ->
                enriched.getOrDefault(
                    row.path("provider").asText() + ":" + row.path("productId").asText(), row))
        .toList();
  }

  @PostMapping("/{provider}/{productId}/messages")
  public ResponseEntity<?> send(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @PathVariable("provider") String provider,
      @PathVariable("productId") String productId,
      @RequestBody JsonNode body) {
    return handle(token, provider, productId, body, false);
  }

  @PostMapping("/search/messages")
  public ResponseEntity<?> searchMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    return handle(token, null, null, body, false);
  }

  @PostMapping("/search/summary")
  public ResponseEntity<?> searchSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    return handle(token, null, null, body, true);
  }

  private ResponseEntity<?> handle(
      String token, String provider, String productId, JsonNode body, boolean summaryOnly) {
    var user = sessions.verify(token);
    boolean listing = provider == null;
    if (!listing) {
      UsedController.provider(provider);
      UsedController.id(productId);
    }
    if (!body.isObject()) throw invalid("상담 입력을 확인하세요");
    body.fieldNames()
        .forEachRemaining(
            field -> {
              if (!Set.of("message", "searches", "candidates", "history", "evidenceVersion")
                  .contains(field)) throw invalid("상담 입력을 확인하세요");
            });
    String question = CommunityController.text(body, "message", 1000);
    var searches = body.path("searches");
    var candidates = body.path("candidates");
    var history = body.path("history");
    if (!searches.isArray()
        || searches.size() > 3
        || !candidates.isArray()
        || candidates.size() > 1200
        || !history.isArray()
        || history.size() > 6) throw invalid("상담 근거 범위를 확인하세요");
    for (var row : history)
      if (!row.isObject()
          || row.size() != 2
          || !Set.of("user", "assistant").contains(row.path("role").asText())
          || !row.path("content").isTextual()
          || row.path("content").asText().isBlank()
          || row.path("content").asText().length() > 1000) throw invalid("대화 문맥을 확인하세요");
    var allowed = new HashSet<String>();
    for (var row : candidates) {
      if (!row.isObject()
          || row.size() != 2
          || !row.path("provider").isTextual()
          || !row.path("productId").isTextual()) throw invalid("비교 후보를 확인하세요");
      UsedController.provider(row.path("provider").asText());
      UsedController.id(row.path("productId").asText());
      allowed.add(row.path("provider").asText() + ":" + row.path("productId").asText());
    }
    JsonNode detail = mapper.createObjectNode();
    if (!listing) {
      detail =
          snapshot(
              cache.key("used-" + provider + "-detail", Map.of("productId", productId)),
              "used-" + provider + "-detail");
      if (!productId.equals(detail.path("product").path("productId").asText())
          || !provider.equals(detail.path("product").path("provider").asText()))
        throw invalid("선택 매물을 확인하세요");
    }
    var rows = new ArrayList<JsonNode>();
    var scopes = mapper.createArrayNode();
    for (var search : searches) {
      if (!search.isObject()
          || search.size() != 2
          || !search.path("provider").isTextual()
          || !search.path("id").isTextual()) throw invalid("검색 근거를 확인하세요");
      String source = search.path("provider").asText();
      UsedController.provider(source);
      var data = snapshot(search.path("id").asText(), "used-" + source + "-search");
      var scope = mapper.createObjectNode();
      scope.put("provider", source);
      scope.put("id", search.path("id").asText());
      for (String field :
          List.of("query", "regionId", "regionName", "fetchedAt", "receivedAt", "matchScope"))
        scope.set(field, data.path(field));
      scope.put("fresh", UsedAgentPolicy.fresh(data));
      scope.put("contentVersion", cache.key("used-search-source-v1", data));
      scopes.add(scope);
      if (!UsedAgentPolicy.fresh(data)) continue;
      for (var row : data.path("items"))
        if (row.isObject()
            && source.equals(row.path("provider").asText())
            && allowed.contains(source + ":" + row.path("productId").asText())) {
          ObjectNode copy = row.deepCopy();
          copy.set("observedAt", data.path("fetchedAt"));
          rows.add(copy);
        }
    }
    // Sorting identity lists makes display sorting irrelevant to cache identity.
    var searchInput = UsedAnalysisCache.searchInput(scopes, rows);
    var searchRows = List.copyOf(rows);
    var searchAnalysis =
        UsedAnalysisCache.load(
            firebase,
            cache,
            mapper,
            "used-search-analysis-v1",
            searchInput,
            UsedAnalysisCache.expires(scopes),
            () -> {
              var analysis = UsedSearchAnalysis.assess(mapper, searchRows);
              analysis.set("productGroups", UsedProductGroups.assess(mapper, searchRows));
              // Keep only aggregates and compact exclusion reasons, not copies of listing text.
              for (var excluded : analysis.withArray("excludedListings"))
                if (excluded.isObject()) ((ObjectNode) excluded).remove("title");
              analysis.put("analysisKey", cache.key("used-search-analysis-v1", searchInput));
              analysis.set("searchScopes", scopes.deepCopy());
              return analysis;
            });
    if (!summaryOnly) quota.consume(user.uid());
    if (!listing) {
      rows = new ArrayList<>(withStoredDetails(rows, detail.path("product")));
    }
    var assessment =
        listing ? searchAnalysis.deepCopy() : UsedAgentPolicy.assess(mapper, detail, rows);
    if (!listing) {
      assessment.set("searchAnalysis", searchAnalysis);
      assessment.put("headline", "상세 API 기반 구매 분석");
      // Reference-only grouping uses original search rows; exclude this listing from its peer
      // statistics.
      assessment.set(
          "selectedSearchGroup",
          UsedProductGroups.selected(mapper, detail.path("product"), searchRows));
    }
    if (!listing)
      assessment
          .withArray("limitations")
          .add(
              "현재 검색 후보 최대 1,200개 중 이미 조회된 상세 최대 60개만 추가 확인합니다. 본문·조건 근거가 없는 후보는 동일 조건 통계에 넣지 않습니다. 별도 검색이나 기간 확장은 하지 않았습니다.");
    if (scopes.isEmpty())
      assessment.withArray("limitations").add("사용할 수 있는 검색 목록이 없어 비교 후보가 없습니다.");
    for (var scope : scopes)
      if (!scope.path("fresh").asBoolean()) {
        assessment.withArray("limitations").add("조회 시각이 없거나 하루가 지난 검색 결과는 가격 비교 후보에서 제외했습니다.");
        break;
      }
    var product = UsedApiEvidence.product(mapper, detail);
    if (!listing) UsedPurchaseJudgment.apply(mapper, assessment, product);
    var detailApi = UsedApiEvidence.metadata(mapper, detail, product);
    var facts = mapper.createObjectNode();
    facts.set("product", product);
    facts.set("assessment", assessment);
    facts.set("searchScopes", scopes);
    facts.set("fetchedAt", detail.path("fetchedAt"));
    facts.put("mode", listing ? "search" : "detail");
    if (!listing) facts.set("detailApi", detailApi);
    ObjectNode versionFacts = facts.deepCopy();
    if (versionFacts.path("assessment").path("detailReport").isObject())
      ((ObjectNode) versionFacts.path("assessment").path("detailReport")).remove("analysisAt");
    String version = cache.key("used-agent-evidence", versionFacts);
    if (body.has("evidenceVersion")
        && (!body.path("evidenceVersion").isTextual()
            || !version.equals(body.path("evidenceVersion").asText())))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "상담 근거가 변경되었습니다. 대화를 초기화하고 다시 질문해 주세요");
    ObjectNode result = assessment.deepCopy();
    result.put("evidenceVersion", version);
    result.put("source", "server-rule");
    result.put("generatedAt", Instant.now().toString());
    result.set("product", product);
    result.set("searchScopes", scopes);
    result.set("fetchedAt", detail.path("fetchedAt"));
    result.put("aiUnavailable", true);
    if (!listing) result.set("detailApi", detailApi);
    result.set("sellerNotes", mapper.createArrayNode());
    if (summaryOnly) {
      result.put("aiUnavailable", false);
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .header("Vary", "Authorization")
          .body(Map.of("data", result));
    }
    try {
      JsonNode safeHistory = history.deepCopy();
      if (!listing)
        for (var row : safeHistory)
          ((ObjectNode) row).put("content", UsedListingFacts.clean(row.path("content").asText()));
      var generated =
          generate(
              facts, version, listing ? question : UsedListingFacts.clean(question), safeHistory);
      String answer =
          listing
              ? generated.path("answer").asText()
              : UsedListingFacts.clean(generated.path("answer").asText());
      if (answer.isBlank()
          || answer.length() > (listing ? 2000 : 4000)
          || !generated.path("sellerNotes").isArray()
          || generated.path("sellerNotes").size() > 5) throw new IllegalArgumentException();
      String description = product.path("description").asText("");
      for (var note : generated.path("sellerNotes")) {
        String quote = note.path("quote").asText();
        if (quote.isBlank() || quote.length() > 500 || !description.contains(quote))
          throw new IllegalArgumentException();
      }
      result.put("answer", answer);
      result.set("sellerNotes", generated.path("sellerNotes"));
      result.put("source", "gemini");
      result.put("aiUnavailable", false);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      /* Show deterministic evidence without external lookup or fabricated data. */
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Vary", "Authorization")
        .body(Map.of("data", result));
  }
}
