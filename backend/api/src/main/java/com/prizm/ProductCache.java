package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Single-process cache of product summaries only; response metadata is always generated per request. */
@Component
public class ProductCache {
    public record Result(JsonNode data, boolean hit) {}
    private record Entry(JsonNode data, Instant expiresAt) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Clock clock;
    public ProductCache() { this(Clock.systemUTC()); }
    ProductCache(Clock clock) { this.clock = clock; }
    public synchronized Result get(String key, Supplier<JsonNode> loader) {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
        var previous = entries.get(key);
        if (previous != null) return new Result(previous.data().deepCopy(), true);
        JsonNode data = loader.get(); // Exceptions are not cached.
        if (entries.size() >= 128) entries.remove(entries.keySet().iterator().next());
        entries.put(key, new Entry(data.deepCopy(), now.plusSeconds(60)));
        return new Result(data.deepCopy(), false);
    }
    public synchronized void invalidateAll() { entries.clear(); }
}
