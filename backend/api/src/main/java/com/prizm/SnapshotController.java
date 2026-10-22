package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class SnapshotController {
    private final FixtureSnapshots snapshots;
    private final CatalogRepository catalog;
    private final String key;
    private final SnapshotCatalogRepository liveCatalog;
    public SnapshotController(FixtureSnapshots snapshots, @org.springframework.beans.factory.annotation.Qualifier("fixtureRepository") CatalogRepository catalog, @Value("${prizm.internal-key}") String key, SnapshotCatalogRepository liveCatalog) {
        this.snapshots = snapshots; this.catalog = catalog; this.key = key; this.liveCatalog = liveCatalog;
    }
    @PostMapping("/internal/fixture-snapshots") public ResponseEntity<?> save(@RequestHeader(value="X-Internal-Key", defaultValue="") String provided, @RequestBody JsonNode body) {
        if (key.isBlank() || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "내부 인증 실패");
        if (!body.path("idempotencyKey").isTextual() || !body.path("dataVersion").isTextual() || !body.path("referenceTime").isTextual()
                || !body.path("productIds").isArray() || body.path("productIds").size() > 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "스냅샷 저장 요청 형식을 확인하세요");
        var ids = new ArrayList<String>();
        for (var id : body.path("productIds")) { if (!id.isTextual()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID는 문자열이어야 합니다"); ids.add(id.asText()); }
        var receipt = snapshots.save(body.path("idempotencyKey").asText(), body.path("dataVersion").asText(), body.path("referenceTime").asText(), ids);
        liveCatalog.refreshAfterWrite();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("data", receipt, "meta", Map.of("source", "firebase-emulator", "synthetic", true,
            "referenceTime", catalog.referenceTime(), "updatedAt", catalog.referenceTime(), "stale", false, "requestId", RequestIds.current())));
    }
}
