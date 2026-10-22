package com.prizm;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    @org.springframework.beans.factory.annotation.Value("${prizm.firebase.mode:emulator}") private String firebaseMode = "emulator";
    private final SessionVerifier sessions;
    private final WishlistStore store;
    private final CatalogRepository catalog;
    public MeController(SessionVerifier sessions, WishlistStore store, CatalogRepository catalog) { this.sessions = sessions; this.store = store; this.catalog = catalog; }
    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "cloud".equals(firebaseMode) ? "firebase" : "firebase-emulator"); meta.put("synthetic", false); meta.put("referenceTime", Instant.now().toString());
        meta.put("updatedAt", null); meta.put("stale", false); meta.put("requestId", RequestIds.current()); return meta;
    }
    private ResponseEntity<?> response(Map<String, Object> body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(body);
    }
    private void validId(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID 형식을 확인하세요");
    }
    @GetMapping public ResponseEntity<?> me(@RequestHeader(value="Authorization", defaultValue="") String authorization) {
        var user = sessions.verify(authorization);
        return response(Map.of("data", user, "meta", meta()));
    }
    @GetMapping("/wishlist") public ResponseEntity<?> list(@RequestHeader(value="Authorization", defaultValue="") String authorization,
            @RequestParam(defaultValue="") String cursor, @RequestParam(defaultValue="20") int limit) {
        var user = sessions.verify(authorization);
        if (limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit는 1~50입니다");
        if (!cursor.isBlank()) validId(cursor);
        var page = store.list(user.uid(), cursor, limit);
        Map<String, Object> body = new LinkedHashMap<>(); body.put("items", page.items()); body.put("nextCursor", page.nextCursor()); body.put("meta", meta());
        return response(body);
    }
    @GetMapping("/wishlist/{id}") public ResponseEntity<?> state(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); validId(id);
        return response(Map.of("data", Map.of("productId", id, "saved", store.contains(user.uid(), id)), "meta", meta()));
    }
    @PutMapping("/wishlist/{id}") public ResponseEntity<?> save(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); validId(id);
        if (catalog.products().stream().noneMatch(p -> id.equals(p.path("id").asText())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다");
        store.save(user.uid(), id);
        return response(Map.of("data", Map.of("productId", id, "saved", true), "meta", meta()));
    }
    @DeleteMapping("/wishlist/{id}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); validId(id); store.delete(user.uid(), id);
        return response(Map.of("data", Map.of("productId", id, "saved", false), "meta", meta()));
    }
}
