package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountDeletionController {
    @org.springframework.beans.factory.annotation.Value("${prizm.firebase.mode:emulator}") private String firebaseMode = "emulator";
    private final SessionVerifier sessions;
    private final AccountDeletion deletion;
    public AccountDeletionController(SessionVerifier sessions, AccountDeletion deletion) { this.sessions = sessions; this.deletion = deletion; }
    @DeleteMapping("/account") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String authorization, @RequestBody JsonNode body) {
        var user = sessions.verifyRecent(authorization);
        if (!body.path("confirm").isBoolean() || !body.path("confirm").asBoolean())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "계정 삭제 확인이 필요합니다");
        String state = deletion.advance(user.uid());
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source", "cloud".equals(firebaseMode) ? "firebase" : "firebase-emulator"); meta.put("synthetic", false); meta.put("referenceTime", Instant.now().toString());
        meta.put("updatedAt", null); meta.put("stale", false); meta.put("requestId", RequestIds.current());
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization")
            .body(Map.of("data", Map.of("state", state), "meta", meta));
    }
}
