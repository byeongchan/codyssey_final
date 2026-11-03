package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Public browsing helpers and operator-only observation/analysis operations. No fixture writes. */
@RestController
@RequestMapping(value = "/api/v1/me", produces = "application/json")
public class DanawaController {
  private final SessionVerifier sessions;
  private final DanawaClient client;
  private final DanawaObservations observations;
  private final LiveDataCache cache;
  private final Set<String> operators = new HashSet<>();

  @Value("${prizm.live.search-cache-seconds:86400}")
  private long searchTtl = 86400;

  @Value("${prizm.live.product-cache-seconds:86400}")
  private long productTtl = 86400;

  @Value("${prizm.live.offers-cache-seconds:86400}")
  private long offersTtl = 86400;

  @jakarta.annotation.PostConstruct
  public void validateCachePolicy() {
    if (List.of(searchTtl, productTtl, offersTtl).stream()
        .anyMatch(value -> value < 1 || value > 604800))
      throw new IllegalArgumentException("실제 데이터 캐시 TTL은 1~604800초 범위로 설정하세요");
  }

  public DanawaController(
      SessionVerifier sessions,
      DanawaClient client,
      DanawaObservations observations,
      @Value("${prizm.operations-uids:}") String uids) {
    this(sessions, client, observations, uids, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DanawaController(
      SessionVerifier sessions,
      DanawaClient client,
      DanawaObservations observations,
      @Value("${prizm.operations-uids:}") String uids,
      LiveDataCache cache) {
    this.sessions = sessions;
    this.client = client;
    this.observations = observations;
    this.cache = cache;
    for (String uid : uids.split(",")) if (!uid.isBlank()) operators.add(uid.strip());
  }

  private void authorize(String token) {
    if (!operators.contains(sessions.verify(token).uid()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "로컬 다나와 조회 권한이 없습니다");
  }

  private ResponseEntity<?> read(String action, Map<String, Object> body) {
    return read(action, body, false);
  }

  private boolean refresh(JsonNode body) {
    if (body.has("refresh") && !body.path("refresh").isBoolean()) throw invalid();
    return body.path("refresh").asBoolean(false);
  }

  private ResponseEntity<?> read(String action, Map<String, Object> body, boolean refresh) {
    if (cache != null) {
      var ttl =
          java.time.Duration.ofSeconds(
              switch (action) {
                case "search" -> searchTtl;
                case "product" -> productTtl;
                default -> offersTtl;
              });
      return cache.response(
          cache.fetch(action, body, ttl, () -> client.read(action, body), refresh));
    }
    var data = client.read(action, body);
    var meta = new LinkedHashMap<String, Object>();
    meta.put("source", "reef-danawa");
    meta.put("synthetic", false);
    meta.put("requestId", RequestIds.current());
    meta.put("referenceTime", data.path("receivedAt").asText());
    meta.put(
        "updatedAt", data.path("fetchedAt").isTextual() ? data.path("fetchedAt").asText() : null);
    meta.put(
        "stale",
        !data.path("fetchedAt").isTextual()
            || DataPolicy.stale(
                java.time.Instant.parse(data.path("fetchedAt").asText()), java.time.Instant.now()));
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Vary", "Authorization")
        .body(Map.of("data", data, "meta", meta));
  }

  private int page(JsonNode body, int max) {
    if (!body.isObject()
        || (body.has("page")
            && (!body.path("page").isIntegralNumber() || !body.path("page").canConvertToInt())))
      throw invalid();
    int value = body.path("page").asInt(1);
    if (value < 1 || value > max) throw invalid();
    return value;
  }

  private String id(JsonNode body) {
    if (!body.path("productId").isTextual()
        || !body.path("productId").asText().matches("[0-9]{1,30}")) throw invalid();
    return body.path("productId").asText();
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "다나와 검색어·상품 ID·페이지 형식을 확인하세요");
  }

  @PostMapping("/danawa/search")
  public ResponseEntity<?> search(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    authorize(token);
    return publicSearch(body);
  }

  ResponseEntity<?> publicSearch(JsonNode body) {
    int page = page(body, 50);
    if (!body.path("query").isTextual()
        || body.path("query").asText().isBlank()
        || body.path("query").asText().strip().length() > 100) throw invalid();
    String categoryId = body.has("categoryId") ? body.path("categoryId").asText() : "";
    if (body.has("categoryId") && !body.path("categoryId").isTextual()) throw invalid();
    DanawaCategories.validate(categoryId);
    var allowed =
        Set.of("query", "categoryId", "page", "sort", "maker", "minPrice", "maxPrice", "refresh");
    body.fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field)) throw invalid();
            });
    String sort = body.path("sort").asText("popular");
    if (body.has("sort") && !body.path("sort").isTextual()) throw invalid();
    if (!Set.of("popular", "price_asc", "price_desc", "newest", "most_reviewed").contains(sort))
      throw invalid();
    var input = new LinkedHashMap<String, Object>();
    input.put("query", LiveDataCache.normalize(body.path("query").asText()));
    input.put("page", page);
    if (!categoryId.isBlank()) input.put("categoryId", categoryId);
    if (!"popular".equals(sort)) input.put("sort", sort);
    if (body.has("maker")) {
      if (!body.path("maker").isTextual() || !body.path("maker").asText().matches("[0-9]{1,20}"))
        throw invalid();
      input.put("maker", body.path("maker").asText());
    }
    SearchFilterPolicy.prices(body, input);
    return read("search", input, refresh(body));
  }

  @PostMapping("/danawa/product")
  public ResponseEntity<?> product(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    authorize(token);
    return publicProduct(body);
  }

  ResponseEntity<?> publicProduct(JsonNode body) {
    page(body, 1);
    return read("product", Map.of("productId", id(body)), refresh(body));
  }

  @PostMapping("/danawa/offers")
  public ResponseEntity<?> offers(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    authorize(token);
    return publicOffers(body);
  }

  ResponseEntity<?> publicOffers(JsonNode body) {
    int page = page(body, 20);
    return read("offers", Map.of("productId", id(body), "page", page), refresh(body));
  }

  ResponseEntity<?> publicPriceHistory(String productId) {
    return publicPriceHistory(productId, false);
  }

  ResponseEntity<?> publicPriceHistory(String productId, boolean refresh) {
    return read("price_history", Map.of("productId", productId), refresh);
  }

  @PostMapping("/danawa/observations")
  public ResponseEntity<?> capture(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @RequestBody JsonNode body) {
    authorize(token);
    int page = page(body, 20);
    String productId = id(body);
    var input = Map.<String, Object>of("productId", productId, "page", page);
    JsonNode data;
    if (cache != null) {
      var view =
          cache.fetch(
              "offers",
              input,
              java.time.Duration.ofSeconds(offersTtl),
              () -> client.read("offers", input));
      if (view.data() == null || view.expiresAt() <= System.currentTimeMillis())
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "판매처 데이터를 수집 중입니다. 갱신 완료 후 저장을 다시 요청하세요");
      data = view.data();
    } else data = client.read("offers", input);
    var receipt = observations.save(data);
    return savedResponse(receipt);
  }

  @GetMapping("/danawa/history/{productId}")
  public ResponseEntity<?> history(
      @RequestHeader(value = "Authorization", defaultValue = "") String token,
      @PathVariable String productId) {
    authorize(token);
    if (!productId.matches("[0-9]{1,30}")) throw invalid();
    return savedResponse(observations.history(productId));
  }

  @GetMapping("/danawa/live-metrics")
  public ResponseEntity<?> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String token) {
    authorize(token);
    return savedResponse(
        Map.of(
            "sharedCache",
            cache.metrics(),
            "http",
            LiveRequestMetrics.snapshot(),
            "collector",
            client.metrics()));
  }

  private ResponseEntity<?> savedResponse(Object data) {
    String now = java.time.Instant.now().toString();
    var meta = new LinkedHashMap<String, Object>();
    meta.put("source", "reef-danawa");
    meta.put("synthetic", false);
    meta.put("requestId", RequestIds.current());
    meta.put("referenceTime", now);
    meta.put("updatedAt", null);
    meta.put("stale", true);
    if (data instanceof Map<?, ?> history && history.containsKey("coverageStart")) {
      for (String field : List.of("currency", "observedDays", "coverageStart", "coverageEnd"))
        meta.put(field, history.get(field));
      meta.put("updatedAt", history.get("latestObservedAt"));
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Vary", "Authorization")
        .body(Map.of("data", data, "meta", meta));
  }
}
