package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class DanawaClient {
    private final ObjectMapper mapper;
    private final String url, key;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public DanawaClient(ObjectMapper mapper, @Value("${prizm.collector-url}") String url, @Value("${prizm.internal-key}") String key) {
        this(mapper, url, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    DanawaClient(ObjectMapper mapper, String url, String key, HttpClient client) { this.mapper = mapper; this.url = url; this.key = key; this.client = client; }
    public JsonNode metrics() {
        try {
            URI base = URI.create(url);
            if (key.isBlank() || !"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(base.getHost()) || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
                throw new IllegalArgumentException();
            var request = HttpRequest.newBuilder(base.resolve("/internal/danawa/metrics")).timeout(Duration.ofSeconds(4)).header("X-Internal-Key", key).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 16000) throw new IllegalArgumentException();
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return mapper.createObjectNode().put("state", "UNAVAILABLE"); }
        catch (Exception ex) { return mapper.createObjectNode().put("state", "UNAVAILABLE"); }
    }
    public JsonNode read(String action, Map<String, Object> input) {
        try {
            URI base = URI.create(url);
            if (!Set.of("search", "product", "offers", "price_history").contains(action) || key.isBlank() || !"http".equals(base.getScheme())
                || !List.of("127.0.0.1", "localhost").contains(Objects.toString(base.getHost(), ""))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !Set.of("", "/").contains(base.getPath()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "로컬 수집기 설정을 확인하세요");
            var request = HttpRequest.newBuilder(base.resolve("/internal/danawa/" + action)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Internal-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ResponseStatusException(
                response.statusCode() == 429 ? HttpStatus.TOO_MANY_REQUESTS : response.statusCode() == 503 || response.statusCode() == 403 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                response.statusCode() == 429 ? "다나와 수집 요청 한도에 도달했습니다" : response.statusCode() == 403 ? "Spring과 수집기의 내부 통신 키를 확인하세요" : "다나와 수집기 설정·외부 응답을 확인하세요");
            if (response.body().length() > 2 * 1024 * 1024) throw new IllegalArgumentException();
            JsonNode result = mapper.readTree(response.body());
            if (!"reef-danawa".equals(result.path("source").asText()) || !result.path("synthetic").isBoolean() || result.path("synthetic").asBoolean()
                || !action.equals(result.path("action").asText())
                || !result.path("localCacheHit").isBoolean() || !result.path("hasMore").isBoolean()) throw new IllegalArgumentException();
            if (!result.path("fetchedAt").isNull()) DataPolicy.instant(result.path("fetchedAt"), "fetchedAt");
            DataPolicy.instant(result.path("receivedAt"), "receivedAt");
            if (!"search".equals(action) && !Objects.equals(input.get("productId"), result.path("productId").asText())) throw new IllegalArgumentException();
            if ("search".equals(action) && (!result.path("items").isArray() || result.path("items").size() > 100)) throw new IllegalArgumentException();
            if ("product".equals(action) && (!result.path("product").isObject() || !Objects.equals(input.get("productId"), result.path("product").path("productId").asText()))) throw new IllegalArgumentException();
            if ("offers".equals(action) && (!result.path("offers").isArray() || result.path("offers").size() > 1000)) throw new IllegalArgumentException();
            if ("price_history".equals(action) && (!result.path("priceHistory").isObject()
                || !Objects.equals(input.get("productId"), result.path("priceHistory").path("productId").asText())
                || !"KRW".equals(result.path("priceHistory").path("currency").asText())
                || !result.path("priceHistory").path("weekly").isArray() || result.path("priceHistory").path("weekly").size() > 12
                || !result.path("priceHistory").path("monthly").isArray() || result.path("priceHistory").path("monthly").size() > 24)) throw new IllegalArgumentException();
            return result;
        } catch (ResponseStatusException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "다나와 조회가 중단되었습니다"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "다나와 수집기 연결·응답을 확인하세요"); }
    }
}
