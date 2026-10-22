package com.prizm;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class HistoryController {
    private final SessionVerifier sessions;
    private final HistoryStore store;
    private final CatalogRepository catalog;
    public HistoryController(SessionVerifier sessions, HistoryStore store, CatalogRepository catalog) {
        this.sessions = sessions; this.store = store; this.catalog = catalog;
    }
    private ResponseEntity<?> response(Map<String, Object> body) {
        var result = new LinkedHashMap<>(body);
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source", "firebase-emulator"); meta.put("synthetic", false); meta.put("referenceTime", Instant.now().toString());
        meta.put("updatedAt", null); meta.put("stale", false); meta.put("requestId", RequestIds.current());
        result.put("meta", meta);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(result);
    }
    private void valid(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID 형식을 확인하세요");
    }
    @GetMapping("/history") public ResponseEntity<?> list(@RequestHeader(value="Authorization", defaultValue="") String authorization,
            @RequestParam(defaultValue="") String cursor, @RequestParam(defaultValue="20") int limit) {
        var user = sessions.verify(authorization);
        if (limit < 1 || limit > 50 || cursor.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 범위를 확인하세요");
        var page = store.list(user.uid(), cursor, limit);
        var result = new LinkedHashMap<String, Object>(); result.put("items", page.items()); result.put("nextCursor", page.nextCursor());
        return response(result);
    }
    @PostMapping("/history/{id}") public ResponseEntity<?> record(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); valid(id);
        if (catalog.products().stream().noneMatch(p -> id.equals(p.path("id").asText()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다");
        store.record(user.uid(), id);
        return response(Map.of("data", Map.of("productId", id, "recorded", true)));
    }
    @DeleteMapping("/history/{id}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); valid(id); store.delete(user.uid(), id);
        return response(Map.of("data", Map.of("productId", id, "recorded", false)));
    }
}
