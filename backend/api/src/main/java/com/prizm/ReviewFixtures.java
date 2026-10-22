package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Repository
public class ReviewFixtures {
    private final JsonNode data;
    public ReviewFixtures(ObjectMapper mapper, CatalogRepository catalog, @Value("${prizm.fixtures}") String directory) throws IOException {
        this(mapper, catalog, directory, "fixture");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewFixtures(ObjectMapper mapper, CatalogRepository catalog, @Value("${prizm.fixtures}") String directory,
            @Value("${prizm.provider}") String provider) throws IOException {
        if ("reef-danawa".equals(provider)) { data = mapper.createObjectNode(); return; }
        data = mapper.readTree(Path.of(directory, "reviews.json").toFile());
        validate(data, catalog);
    }
    static void validate(JsonNode data, CatalogRepository catalog) {
        require(data != null && data.path("schemaVersion").isIntegralNumber() && data.path("schemaVersion").canConvertToInt() && data.path("schemaVersion").asInt() == 1, "리뷰 schemaVersion 오류");
        Instant stamp = DataPolicy.instant(data.path("updatedAt"), "reviews.updatedAt");
        require(!stamp.isAfter(Instant.parse(catalog.referenceTime())), "미래 리뷰 집계 시각");
        require(data.path("products").isArray(), "리뷰 상품 배열 필요");
        Set<String> expected = new HashSet<>(), found = new HashSet<>(), ids = new HashSet<>();
        catalog.products().forEach(p -> expected.add(p.path("id").asText()));
        for (var product : data.path("products")) {
            String id = product.path("productId").asText();
            require(expected.contains(id) && found.add(id), "리뷰 상품 참조/중복 오류");
            require(product.path("supported").isBoolean() && product.path("reviews").isArray(), "리뷰 지원 상태/배열 오류");
            require(product.path("reviews").size() <= 50 && (product.path("supported").asBoolean() || product.path("reviews").isEmpty()), "리뷰 표본 수 오류");
            for (var review : product.path("reviews")) {
                require(review.path("id").isTextual() && review.path("id").asText().matches("[A-Za-z0-9_-]{1,128}") && ids.add(review.path("id").asText()), "리뷰 ID 오류");
                require(review.path("rating").isIntegralNumber() && review.path("rating").canConvertToInt() && review.path("rating").asInt() >= 1 && review.path("rating").asInt() <= 5, "리뷰 별점 오류");
                require(review.path("text").isTextual() && review.path("text").asText().startsWith("[합성 리뷰]") && review.path("text").asText().length() <= 1000, "합성 리뷰 본문 오류");
                for (String field : List.of("pros", "cons")) {
                    var tags = review.path(field); require(tags.isArray() && tags.size() <= 5, "리뷰 태그 배열 오류");
                    Set<String> unique = new HashSet<>();
                    for (var tag : tags) require(tag.isTextual() && !tag.asText().isBlank() && tag.asText().length() <= 40 && unique.add(tag.asText()), "리뷰 태그 오류");
                }
            }
        }
        require(found.equals(expected), "모든 상품의 리뷰 지원 상태가 필요합니다");
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    public JsonNode product(String id) { for (var item : data.path("products")) if (id.equals(item.path("productId").asText())) return item; return null; }
    public String updatedAt() { return data.path("updatedAt").asText(); }
}
