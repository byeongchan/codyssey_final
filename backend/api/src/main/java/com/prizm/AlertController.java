package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class AlertController {
    private final SessionVerifier sessions;
    private final AlertStore store;
    private final CatalogRepository catalog;
    private final PriceObservations prices;
    public AlertController(SessionVerifier sessions, AlertStore store, CatalogRepository catalog, PriceObservations prices) {
        this.sessions = sessions; this.store = store; this.catalog = catalog; this.prices = prices;
    }
    private ResponseEntity<?> response(Object data) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "firebase-emulator"); metadata.put("synthetic", false); metadata.put("referenceTime", Instant.now().toString());
        metadata.put("updatedAt", null); metadata.put("stale", false); metadata.put("requestId", RequestIds.current());
        Map<String, Object> body = new LinkedHashMap<>();
        if (data instanceof AlertStore.Page<?> page) { body.put("items", page.items()); body.put("nextCursor", page.nextCursor()); }
        else body.put("data", data);
        body.put("meta", metadata);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(body);
    }
    private void id(String value) { if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw bad("상품 ID 형식을 확인하세요"); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private long target(JsonNode value) {
        try { return DataPolicy.amount(value, "targetPrice", true); }
        catch (IllegalArgumentException ex) { throw bad("목표가는 1원 이상 1조 원 이하의 정수여야 합니다"); }
    }
    private void body(JsonNode body) { if (body == null || !body.isObject()) throw bad("JSON 객체가 필요합니다"); }
    private void page(String cursor, int limit) { if (!cursor.isBlank()) id(cursor); if (limit < 1 || limit > 50) throw bad("limit는 1~50입니다"); }
    private boolean enabled(JsonNode body, boolean fallback) {
        if (!body.has("enabled")) return fallback;
        if (!body.path("enabled").isBoolean()) throw bad("enabled는 true 또는 false여야 합니다");
        return body.path("enabled").asBoolean();
    }
    @GetMapping("/alerts") public ResponseEntity<?> list(@RequestHeader(value="Authorization", defaultValue="") String header,
            @RequestParam(defaultValue="") String cursor, @RequestParam(defaultValue="20") int limit) {
        var user = sessions.verify(header); page(cursor, limit); return response(store.list(user.uid(), cursor, limit));
    }
    @GetMapping("/alerts/{id}") public ResponseEntity<?> get(@RequestHeader(value="Authorization", defaultValue="") String header, @PathVariable String id) {
        var user = sessions.verify(header); id(id); return response(store.get(user.uid(), id));
    }
    @PostMapping("/alerts") public ResponseEntity<?> create(@RequestHeader(value="Authorization", defaultValue="") String header, @RequestBody JsonNode input) {
        var user = sessions.verify(header); body(input);
        if (!input.path("productId").isTextual()) throw bad("productId가 필요합니다");
        String id = input.path("productId").asText(); id(id);
        if (catalog.products().stream().noneMatch(p -> id.equals(p.path("id").asText()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다");
        store.configure(user.uid(), id, target(input.path("targetPrice")), enabled(input, true), false);
        store.evaluate(user.uid(), id, prices.current(id));
        return response(store.get(user.uid(), id));
    }
    @PatchMapping("/alerts/{id}") public ResponseEntity<?> update(@RequestHeader(value="Authorization", defaultValue="") String header, @PathVariable String id, @RequestBody JsonNode input) {
        var user = sessions.verify(header); id(id); body(input);
        if (!input.has("targetPrice") && !input.has("enabled")) throw bad("targetPrice 또는 enabled가 필요합니다");
        store.configure(user.uid(), id, input.has("targetPrice") ? target(input.path("targetPrice")) : null, input.has("enabled") ? enabled(input, true) : null, true);
        store.evaluate(user.uid(), id, prices.current(id));
        return response(store.get(user.uid(), id));
    }
    @DeleteMapping("/alerts/{id}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String header, @PathVariable String id) {
        var user = sessions.verify(header); id(id); store.delete(user.uid(), id); return response(Map.of("productId", id, "deleted", true));
    }
    @PostMapping("/alerts/evaluate") public ResponseEntity<?> evaluate(@RequestHeader(value="Authorization", defaultValue="") String header,
            @RequestParam(defaultValue="") String cursor, @RequestParam(defaultValue="20") int limit) {
        var user = sessions.verify(header); page(cursor, limit);
        var records = store.list(user.uid(), cursor, limit); int notified = 0;
        for (var alert : records.items()) if (store.evaluate(user.uid(), alert.id(), prices.current(alert.productId()))) notified++;
        Map<String, Object> result = new LinkedHashMap<>(); result.put("checked", records.items().size()); result.put("notified", notified); result.put("nextCursor", records.nextCursor());
        return response(result);
    }
    @GetMapping("/notifications") public ResponseEntity<?> notifications(@RequestHeader(value="Authorization", defaultValue="") String header,
            @RequestParam(defaultValue="") String cursor, @RequestParam(defaultValue="20") int limit) {
        var user = sessions.verify(header); page(cursor, limit); return response(store.notifications(user.uid(), cursor, limit));
    }
    @PatchMapping("/notifications/{id}") public ResponseEntity<?> read(@RequestHeader(value="Authorization", defaultValue="") String header, @PathVariable String id, @RequestBody JsonNode input) {
        var user = sessions.verify(header); id(id); body(input);
        if (!input.path("read").isBoolean() || !input.path("read").asBoolean()) throw bad("read=true만 지원합니다");
        store.markRead(user.uid(), id); return response(Map.of("id", id, "read", true));
    }
}
