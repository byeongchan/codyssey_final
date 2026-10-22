package com.prizm;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DiscoveryController {
    private final DiscoveryService service;
    private final CatalogRepository catalog;
    public DiscoveryController(DiscoveryService service, CatalogRepository catalog) { this.service = service; this.catalog = catalog; }
    private Map<String, Object> meta(String updatedAt, boolean stale) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source", "fixture"); meta.put("synthetic", true); meta.put("referenceTime", catalog.referenceTime());
        meta.put("updatedAt", updatedAt); meta.put("stale", stale); meta.put("requestId", RequestIds.current());
        meta.put("catalogVersion", Objects.toString(catalog.dataVersion(), "")); return meta;
    }
    @GetMapping("/products/{id}/reviews") public Object reviews(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID 형식을 확인하세요");
        var data = service.reviews(id); return Map.of("data", data, "meta", meta(data.updatedAt(), data.stale()));
    }
    @GetMapping("/deals") public Object deals(@RequestParam(defaultValue="0") int cursor, @RequestParam(defaultValue="20") int limit) {
        if (cursor < 0 || limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 범위를 확인하세요");
        var all = service.deals();
        if (cursor > all.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서");
        int end = Math.min(cursor + limit, all.size()); var items = all.subList(cursor, end);
        var result = new LinkedHashMap<String, Object>(); result.put("items", items); result.put("nextCursor", end < all.size() ? Integer.toString(end) : null);
        result.put("criteria", Map.of("windowDays", 30, "minimumObservedDays", 7, "minimumBelowAveragePercent", 2));
        String updated = items.stream().map(DiscoveryService.Deal::observedAt).max(Comparator.comparing(java.time.Instant::parse)).orElse(null);
        result.put("meta", meta(updated, false)); return result;
    }
}
