package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SnapshotRetentionController {
    private final SnapshotRetention retention;
    private final String key;
    public SnapshotRetentionController(SnapshotRetention retention, @Value("${prizm.internal-key}") String key) { this.retention = retention; this.key = key; }
    @PostMapping("/internal/snapshot-retention")
    public ResponseEntity<?> run(@RequestHeader(value="X-Internal-Key", defaultValue="") String provided, @RequestBody JsonNode body) {
        if (key.isBlank() || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "내부 인증 실패");
        if (!body.isObject() || (body.has("dryRun") && !body.path("dryRun").isBoolean())
            || (body.has("planId") && (!body.path("planId").isTextual() || !body.path("planId").asText().matches("[a-f0-9]{64}"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정리 요청 형식을 확인하세요");
        boolean dryRun = body.path("dryRun").asBoolean(true);
        if (!dryRun && !body.has("planId")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "삭제 전 확인한 planId가 필요합니다");
        var result = retention.run(dryRun, body.path("planId").asText(""));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("data", result,
            "meta", Map.of("source", "firebase-emulator", "synthetic", true, "requestId", RequestIds.current())));
    }
}
