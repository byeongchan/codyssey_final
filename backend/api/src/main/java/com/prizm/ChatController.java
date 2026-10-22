package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class ChatController {
    private final SessionVerifier sessions;
    private final ChatStore store;
    public ChatController(SessionVerifier sessions, ChatStore store) { this.sessions = sessions; this.store = store; }
    private void validId(String id) { if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID 형식을 확인하세요"); }
    private ResponseEntity<?> response(Object data) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source", "firebase-emulator"); meta.put("synthetic", true); meta.put("referenceTime", Instant.now().toString());
        meta.put("updatedAt", null); meta.put("stale", false); meta.put("requestId", RequestIds.current());
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(Map.of("data", data, "meta", meta));
    }
    @GetMapping("/conversations/{id}") public ResponseEntity<?> get(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); validId(id); return response(store.get(user.uid(), id));
    }
    @PostMapping("/conversations/{id}/messages") public ResponseEntity<?> send(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id, @RequestBody JsonNode body) {
        var user = sessions.verify(authorization); validId(id);
        if (!body.path("message").isTextual() || body.path("message").asText().isBlank() || body.path("message").asText().length() > 1000
                || !body.path("requestId").isTextual() || !body.path("requestId").asText().matches("[A-Za-z0-9_-]{16,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 1~1000자, 요청 ID는 16~64자 영문·숫자·밑줄·하이픈으로 입력하세요");
        return response(store.send(user.uid(), id, body.path("requestId").asText(), body.path("message").asText().strip()));
    }
    @DeleteMapping("/conversations/{id}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String authorization, @PathVariable String id) {
        var user = sessions.verify(authorization); validId(id); store.delete(user.uid(), id); return response(Map.of("productId", id, "deleted", true));
    }
}
