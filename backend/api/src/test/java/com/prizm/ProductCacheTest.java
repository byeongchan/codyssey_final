package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProductCacheTest {
    static final class Time extends Clock {
        Instant now = Instant.parse("2026-10-22T03:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void ttlVersionAndInvalidationNeverShareMutableResponseData() {
        var clock = new Time(); var cache = new ProductCache(clock); var calls = new AtomicInteger(); var mapper = new ObjectMapper();
        java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> load = () -> { calls.incrementAndGet(); return mapper.createObjectNode().put("price", 100); };
        var first = cache.get("v1:reference:p", load); assertFalse(first.hit());
        ((ObjectNode)first.data()).put("price", 0);
        var second = cache.get("v1:reference:p", load); assertTrue(second.hit()); assertEquals(100, second.data().path("price").asInt()); assertEquals(1, calls.get());
        assertFalse(cache.get("v2:reference:p", load).hit());
        clock.now = clock.now.plusSeconds(60); assertFalse(cache.get("v1:reference:p", load).hit());
        cache.invalidateAll(); assertFalse(cache.get("v1:reference:p", load).hit());
    }
    @Test void failedLoadsAndEvictedEntriesAreNotReturnedAsHits() {
        var cache = new ProductCache(); var mapper = new ObjectMapper();
        assertThrows(IllegalStateException.class, () -> cache.get("bad", () -> { throw new IllegalStateException(); }));
        assertFalse(cache.get("bad", () -> mapper.createObjectNode()).hit());
        for (int i = 0; i < 128; i++) cache.get("key-" + i, () -> mapper.createObjectNode());
        assertFalse(cache.get("bad", () -> mapper.createObjectNode()).hit());
    }
}
