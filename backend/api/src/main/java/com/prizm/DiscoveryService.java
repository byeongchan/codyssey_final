package com.prizm;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class DiscoveryService {
    public record Tag(String label, int count) {}
    public record Review(String id, int rating, String text) {}
    public record ReviewSummary(String productId, String status, int sampleCount, Double averageRating,
        int positiveCount, int neutralCount, int negativeCount, List<Tag> pros, List<Tag> cons,
        List<Review> reviews, String updatedAt, boolean stale, String method) {}
    public record Deal(String productId, String title, String categoryId, long currentPrice, double averagePrice,
        double belowAveragePercent, int observedDays, String coverageStart, String coverageEnd, String observedAt) {}
    private final CatalogRepository catalog;
    private final PriceObservations observations;
    private final ReviewFixtures fixtures;
    public DiscoveryService(CatalogRepository catalog, PriceObservations observations, ReviewFixtures fixtures) {
        this.catalog = catalog; this.observations = observations; this.fixtures = fixtures;
    }
    private List<Tag> tags(com.fasterxml.jackson.databind.JsonNode reviews, String field) {
        Map<String, Integer> counts = new HashMap<>();
        reviews.forEach(r -> r.path(field).forEach(t -> counts.merge(t.asText(), 1, Integer::sum)));
        return counts.entrySet().stream().map(e -> new Tag(e.getKey(), e.getValue()))
            .sorted(Comparator.comparingInt(Tag::count).reversed().thenComparing(Tag::label)).limit(3).toList();
    }
    public ReviewSummary reviews(String id) {
        if (catalog.products().stream().noneMatch(p -> id.equals(p.path("id").asText()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다");
        var product = fixtures.product(id); var raw = product.path("reviews"); int count = raw.size();
        boolean stale = DataPolicy.stale(Instant.parse(fixtures.updatedAt()), Instant.parse(catalog.referenceTime()));
        var reviews = new ArrayList<Review>(); int positive = 0, neutral = 0, negative = 0, sum = 0;
        for (var r : raw) {
            int rating = r.path("rating").asInt(); sum += rating;
            if (rating >= 4) positive++; else if (rating == 3) neutral++; else negative++;
            reviews.add(new Review(r.path("id").asText(), rating, r.path("text").asText()));
        }
        String status = !product.path("supported").asBoolean() ? "UNSUPPORTED" : count == 0 ? "EMPTY" : stale ? "STALE" : count < 3 ? "INSUFFICIENT_DATA" : "AVAILABLE";
        return new ReviewSummary(id, status, count, count == 0 ? null : (double) sum / count, positive, neutral, negative,
            "AVAILABLE".equals(status) ? tags(raw, "pros") : List.of(), "AVAILABLE".equals(status) ? tags(raw, "cons") : List.of(),
            reviews, fixtures.updatedAt(), stale, "fixture-rating-and-authored-tags-no-ai");
    }
    public List<Deal> deals() {
        return CatalogViews.read(catalog, this::dealsPinned);
    }
    private List<Deal> dealsPinned() {
        var reference = Instant.parse(catalog.referenceTime()); var day = reference.atZone(DataPolicy.SEOUL).toLocalDate();
        var result = new ArrayList<Deal>();
        for (var product : catalog.products()) {
            String id = product.path("id").asText(); var current = observations.current(id);
            if (!current.available()) continue;
            var history = catalog.prices(id).stream().filter(p -> {
                var date = LocalDate.parse(p.path("date").asText());
                return !date.isBefore(day.minusDays(29)) && !date.isAfter(day);
            }).sorted(Comparator.comparing(p -> p.path("date").asText())).toList();
            int count = history.size();
            if (count < 7 || DataPolicy.historyStale(history.get(count - 1).path("date").asText(), reference)) continue;
            long sum = history.stream().mapToLong(p -> p.path("comparablePrice").asLong()).sum();
            // Exact integer comparison avoids admitting a 1.999...% reduction rounded to 2%.
            if (sum <= 0 || current.price() * count * 100 > sum * 98) continue;
            double average = (double) sum / count;
            double difference = BigDecimal.valueOf(sum - current.price() * count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sum), 12, RoundingMode.HALF_UP).doubleValue();
            result.add(new Deal(id, product.path("title").asText(), product.path("categoryId").asText(), current.price(), average,
                difference, count, history.get(0).path("date").asText(), history.get(count - 1).path("date").asText(), current.observedAt().toString()));
        }
        return result.stream().sorted(Comparator.comparingDouble(Deal::belowAveragePercent).reversed().thenComparing(Deal::productId)).toList();
    }
}
