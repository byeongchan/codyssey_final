package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class HttpCollectionSource implements CollectionSource {
    private final CatalogRepository catalog;
    private final ObjectMapper mapper;
    private final String url, key;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpCollectionSource(@org.springframework.beans.factory.annotation.Qualifier("fixtureRepository") CatalogRepository catalog, ObjectMapper mapper, @Value("${prizm.collector-url}") String url, @Value("${prizm.internal-key}") String key) {
        this(catalog, mapper, url, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    HttpCollectionSource(CatalogRepository catalog, ObjectMapper mapper, String url, String key, HttpClient client) {
        this.catalog = catalog; this.mapper = mapper; this.url = url; this.key = key; this.client = client;
    }
    public Result collect(Query query) {
        try {
            var base = URI.create(url);
            if (key.isBlank() || !"http".equals(base.getScheme()) || base.getHost() == null || !List.of("127.0.0.1", "localhost").contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath())))
                throw new Failure("COLLECTOR_CONFIGURATION", "로컬 수집기 주소와 내부 키 설정을 확인하세요", false);
            var body = Map.of("q", query.q(), "categoryId", query.categoryId(), "scenario", query.scenario(),
                "referenceTime", catalog.referenceTime(), "dataVersion", catalog.dataVersion());
            var request = HttpRequest.newBuilder(base.resolve("/internal/collect")).timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json").header("X-Internal-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            switch (response.statusCode()) {
                case 409 -> throw new Failure("FIXTURE_MISMATCH", "API와 수집기의 더미 파일 버전이 다릅니다. 같은 파일로 API를 재시작하세요", false);
                case 422 -> throw new Failure("PROVIDER_UNSUPPORTED", "더미 공급자가 이 요청을 지원하지 않습니다", false);
                case 429 -> throw new Failure("PROVIDER_RATE_LIMITED", "더미 공급자 요청 한도입니다. 잠시 후 다시 시도하세요", true);
                case 403 -> throw new Failure("COLLECTOR_CONFIGURATION", "API와 수집기의 내부 키 설정을 확인하세요", false);
                case 200 -> { }
                default -> throw new Failure("COLLECTOR_UNAVAILABLE", "로컬 수집기 상태를 확인하고 다시 시도하세요", true);
            }
            if (response.body().length() > 65536) throw invalid();
            var result = mapper.readTree(response.body());
            if (!"fixture".equals(result.path("source").asText()) || !result.path("synthetic").isBoolean() || !result.path("synthetic").asBoolean()
                    || !"READ_ONLY".equals(result.path("mode").asText()) || !catalog.dataVersion().equals(result.path("dataVersion").asText())
                    || !catalog.referenceTime().equals(result.path("referenceTime").asText()) || !result.path("productIds").isArray()
                    || result.path("productIds").size() > catalog.products().size()) throw invalid();
            var ids = new ArrayList<String>();
            for (var id : result.path("productIds")) { if (!id.isTextual()) throw invalid(); ids.add(id.asText()); }
            var expected = "empty".equals(query.scenario()) ? List.<String>of() : catalog.products().stream()
                .filter(p -> p.path("title").asText().toLowerCase(Locale.ROOT).contains(query.q()) && (query.categoryId().isEmpty() || query.categoryId().equals(p.path("categoryId").asText())))
                .map(p -> p.path("id").asText()).sorted().toList();
            if (!ids.equals(expected)) throw invalid();
            return new Result(List.copyOf(ids), catalog.dataVersion(), catalog.referenceTime());
        } catch (Failure failure) { throw failure; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failure("COLLECTOR_UNAVAILABLE", "수집 요청이 중단되었습니다", true); }
        catch (IllegalArgumentException ex) { throw new Failure("COLLECTOR_CONFIGURATION", "수집기 주소와 내부 키 설정을 확인하세요", false); }
        catch (Exception ex) { throw new Failure("COLLECTOR_UNAVAILABLE", "로컬 수집기 연결·응답을 확인하세요", true); }
    }
    private Failure invalid() { return new Failure("INVALID_COLLECTOR_RESULT", "수집기 응답의 상품·버전·근거가 일치하지 않습니다", false); }
}
