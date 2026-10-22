package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me/danawa/wishlist")
public class DanawaWishlistController {
    private final SessionVerifier sessions;
    private final DanawaWishlist wishlist;
    public DanawaWishlistController(SessionVerifier sessions, DanawaWishlist wishlist) { this.sessions = sessions; this.wishlist = wishlist; }
    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization")
            .body(Map.of("data", data, "meta", Map.of("requestId", RequestIds.current(), "source", "firebase", "synthetic", false)));
    }
    @GetMapping public ResponseEntity<?> list(@RequestHeader(value="Authorization", defaultValue="") String token) {
        return response(wishlist.list(sessions.verify(token).uid()));
    }
    @PutMapping("/{productId}") public ResponseEntity<?> save(@RequestHeader(value="Authorization", defaultValue="") String token,
        @PathVariable String productId, @RequestBody JsonNode body) {
        var user = sessions.verify(token); DanawaWishlist.validId(productId);
        if (!body.isObject() || body.size() > 1 || (body.size() == 1 && !body.has("catalogProductId")) || (body.has("catalogProductId") && !body.path("catalogProductId").isTextual()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "찜 요청을 확인하세요");
        String family = body.has("catalogProductId") ? body.path("catalogProductId").asText() : productId;
        DanawaWishlist.validId(family); wishlist.save(user.uid(), productId, family);
        return response(Map.of("productId", productId, "saved", true));
    }
    @DeleteMapping("/{productId}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String productId) {
        var user = sessions.verify(token); DanawaWishlist.validId(productId); wishlist.delete(user.uid(), productId);
        return response(Map.of("productId", productId, "saved", false));
    }
}
