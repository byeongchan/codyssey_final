package com.prizm;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CatalogSourceController {
    private final SnapshotCatalogRepository catalog;
    public CatalogSourceController(SnapshotCatalogRepository catalog) { this.catalog = catalog; }
    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("data", data, "meta", Map.of("source", "fixture", "synthetic", true,
            "referenceTime", catalog.referenceTime(), "updatedAt", catalog.referenceTime(), "stale", false, "requestId", RequestIds.current())));
    }
    @GetMapping("/catalog-source") public ResponseEntity<?> get() { return response(catalog.status()); }
    @PostMapping("/catalog-source/refresh") public ResponseEntity<?> refresh() { return response(catalog.refresh()); }
}
