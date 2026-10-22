package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.StreamSupport;

@Repository
public class FixtureRepository implements CatalogRepository {
    private final JsonNode catalog;
    private final JsonNode prices;
    private final String referenceTime;
    private final String dataVersion;
    public FixtureRepository(ObjectMapper mapper, @Value("${prizm.fixtures}") String directory,
            @Value("${prizm.provider}") String provider, @Value("${prizm.environment}") String environment,
            @Value("${prizm.reference-time}") String referenceOverride) throws IOException {
        if ("reef-danawa".equals(provider) && "development".equals(environment)) {
            catalog = mapper.createObjectNode(); prices = mapper.createObjectNode();
            referenceTime = java.time.Instant.now().toString(); dataVersion = "live-no-fixtures";
            return;
        }
        if (!"fixture".equals(provider) || !"development".equals(environment)) {
            throw new IllegalStateException("Only development fixture mode is implemented");
        }
        byte[] catalogBytes = java.nio.file.Files.readAllBytes(Path.of(directory, "catalog.json"));
        byte[] pricesBytes = java.nio.file.Files.readAllBytes(Path.of(directory, "prices.json"));
        catalog = mapper.readTree(catalogBytes);
        prices = mapper.readTree(pricesBytes);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(catalogBytes); digest.update(pricesBytes);
            dataVersion = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        // 잘못된 데이터로 기동해 0원 최저가/왜곡된 평균을 제공하는 대신 시작 단계에서 거부한다.
        DataPolicy.validate(catalog, prices);
        String candidate = referenceOverride.isBlank() ? catalog.path("referenceTime").asText() : referenceOverride;
        java.time.Instant reference = DataPolicy.instant(mapper.getNodeFactory().textNode(candidate), "FIXTURE_REFERENCE_TIME");
        if (reference.isBefore(DataPolicy.instant(catalog.path("referenceTime"), "referenceTime"))) {
            throw new IllegalArgumentException("FIXTURE_REFERENCE_TIME은 원본 기준 시각 이전일 수 없습니다");
        }
        referenceTime = reference.toString();
    }
    private List<JsonNode> list(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }
    public List<JsonNode> products() { return list(catalog.path("products")); }
    public List<JsonNode> categories() { return list(catalog.path("categories")); }
    public List<JsonNode> offers(String id) {
        return list(prices.path("offers")).stream().filter(n -> id.equals(n.path("productId").asText())).toList();
    }
    public List<JsonNode> prices(String id) {
        return list(prices.path("dailyPrices")).stream().filter(n -> id.equals(n.path("productId").asText())).toList();
    }
    public String referenceTime() { return referenceTime; }
    public String dataVersion() { return dataVersion; }
}
