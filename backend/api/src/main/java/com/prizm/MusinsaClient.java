package com.prizm;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MusinsaClient {
  private final ObjectMapper mapper;
  private final String url, key;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public MusinsaClient(
      ObjectMapper mapper,
      @Value("${prizm.collector-url}") String url,
      @Value("${prizm.internal-key}") String key) {
    this.mapper = mapper;
    this.url = url;
    this.key = key;
  }

  public JsonNode read(String action, Map<String, Object> input) {
    try {
      URI base = URI.create(url);
      if (!Set.of("search", "product", "reviews").contains(action)
          || key.isBlank()
          || !"http".equals(base.getScheme())
          || !Set.of("127.0.0.1", "localhost").contains(Objects.toString(base.getHost(), ""))
          || base.getUserInfo() != null
          || base.getQuery() != null
          || base.getFragment() != null
          || !Set.of("", "/").contains(base.getPath()))
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "무신사 수집기 설정을 확인하세요");
      var request =
          HttpRequest.newBuilder(base.resolve("/internal/musinsa/" + action))
              .timeout(Duration.ofSeconds(16))
              .header("Content-Type", "application/json")
              .header("X-Internal-Key", key)
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        int status = response.statusCode();
        if (status != 200)
          throw new ResponseStatusException(
              switch (status) {
                case 404 -> HttpStatus.NOT_FOUND;
                case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
              },
              switch (status) {
                case 404 -> "상품 또는 요청한 페이지가 없습니다";
                case 422 -> "패션·스포츠 상품만 조회할 수 있습니다";
                case 429 -> "무신사 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요";
                default -> "무신사 수집기 설정(REEF_MUSINSA_ENABLED)·키·외부 응답을 확인하세요";
              });
        byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException();
        var data = mapper.readTree(bytes);
        if (!"reef-musinsa".equals(data.path("source").asText())
            || !data.path("synthetic").isBoolean()
            || data.path("synthetic").asBoolean()
            || !action.equals(data.path("action").asText())
            || !data.path("hasMore").isBoolean()
            || !data.path("localCacheHit").isBoolean()) throw new IllegalArgumentException();
        DataPolicy.instant(data.path("receivedAt"), "receivedAt");
        if (!data.path("fetchedAt").isNull())
          DataPolicy.instant(data.path("fetchedAt"), "fetchedAt");
        if (!"search".equals(action)
            && !Objects.equals(input.get("productId"), data.path("productId").asText()))
          throw new IllegalArgumentException();
        if ("search".equals(action)
            && (!data.path("items").isArray() || data.path("items").size() > 12))
          throw new IllegalArgumentException();
        if ("product".equals(action)
            && (!data.path("product").isObject()
                || !Objects.equals(
                    input.get("productId"), data.path("product").path("productId").asText())))
          throw new IllegalArgumentException();
        if ("reviews".equals(action)
            && (!data.path("reviews").isArray() || data.path("reviews").size() > 20))
          throw new IllegalArgumentException();
        return data;
      }
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "무신사 조회가 중단되었습니다");
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "무신사 수집기 연결·응답을 확인하세요");
    }
  }
}
