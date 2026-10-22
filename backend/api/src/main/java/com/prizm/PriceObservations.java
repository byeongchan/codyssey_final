package com.prizm;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Comparator;

@Service
public class PriceObservations {
    public record Observation(Long price, Instant observedAt) { public boolean available() { return price != null && price > 0 && observedAt != null; } }
    private final CatalogRepository catalog;
    public PriceObservations(CatalogRepository catalog) { this.catalog = catalog; }
    public Observation current(String productId) {
        return CatalogViews.read(catalog, () -> currentPinned(productId));
    }
    private Observation currentPinned(String productId) {
        if (catalog.products().stream().noneMatch(p -> productId.equals(p.path("id").asText()))) return new Observation(null, null);
        Instant reference = Instant.parse(catalog.referenceTime());
        return catalog.offers(productId).stream()
                .filter(o -> "IN_STOCK".equals(o.path("availability").asText()) && "KRW".equals(o.path("currency").asText()) && o.path("shippingFee").isNumber())
                .filter(o -> !DataPolicy.stale(Instant.parse(o.path("observedAt").asText()), reference))
                .filter(o -> !Instant.parse(o.path("observedAt").asText()).isAfter(reference))
                .map(o -> new Observation(o.path("productPrice").asLong() + o.path("shippingFee").asLong() - o.path("unconditionalDiscount").asLong(), Instant.parse(o.path("observedAt").asText())))
                .min(Comparator.comparing(Observation::price).thenComparing(Observation::observedAt, Comparator.reverseOrder()))
                .orElse(new Observation(null, null));
    }
}
