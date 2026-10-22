package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public browsing shares input validation, collector cache and upstream request limits. */
@RestController
@RequestMapping("/api/v1/danawa")
public class PublicDanawaController {
    private final DanawaController danawa;
    private final LiveDataCache cache;
    public PublicDanawaController(DanawaController danawa) { this(danawa, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public PublicDanawaController(DanawaController danawa, LiveDataCache cache) { this.danawa = danawa; this.cache = cache; }
    @PostMapping("/search") public ResponseEntity<?> search(@RequestBody JsonNode body) { return danawa.publicSearch(body); }
    @PostMapping("/product") public ResponseEntity<?> product(@RequestBody JsonNode body) { return danawa.publicProduct(body); }
    @PostMapping("/offers") public ResponseEntity<?> offers(@RequestBody JsonNode body) { return danawa.publicOffers(body); }
    @PostMapping("/price_history") public ResponseEntity<?> priceHistory(@RequestBody JsonNode body) {
        if (!body.isObject() || body.size() != 1 || !body.path("productId").isTextual() || !body.path("productId").asText().matches("[0-9]{1,30}"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "상품 ID를 확인하세요");
        return danawa.publicPriceHistory(body.path("productId").asText());
    }
    @GetMapping("/jobs/{jobId}") public ResponseEntity<?> job(@PathVariable String jobId) { return cache.response(cache.job(jobId, false)); }
    @GetMapping("/catalog") public ResponseEntity<?> catalogue(@RequestParam(defaultValue="") String categoryId) {
        var data = cache.catalogue(DanawaCategories.validate(categoryId));
        return cache.response(new LiveDataCache.View("", "search", data, "SUCCEEDED", 0, null));
    }
    @GetMapping("/categories") public ResponseEntity<?> categories() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(java.util.Map.of("data", DanawaCategories.ALL,
            "meta", java.util.Map.of("requestId", RequestIds.current(), "source", "server-config", "synthetic", false)));
    }
}
