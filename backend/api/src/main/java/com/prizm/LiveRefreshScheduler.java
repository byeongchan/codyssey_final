package com.prizm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Explicit opt-in: at most five recently collected catalogue products per six-hour tick. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="prizm.live.refresh-enabled", havingValue="true")
public class LiveRefreshScheduler {
    private final LiveDataCache cache;
    private final DanawaClient collector;
    private final DanawaObservations observations;
    public LiveRefreshScheduler(LiveDataCache cache, DanawaClient collector, DanawaObservations observations) {
        this.cache = cache; this.collector = collector; this.observations = observations;
    }
    @Scheduled(fixedDelayString="${prizm.live.refresh-millis:21600000}", initialDelayString="${prizm.live.refresh-millis:21600000}")
    public void refresh() {
        try {
            int count = 0;
            for (var item : cache.catalogue("").path("items")) {
                if (++count > 5) break;
                String id = item.path("productId").asText();
                if (!id.matches("[0-9]{1,30}")) continue;
                var input = Map.<String, Object>of("productId", id, "page", 1);
                cache.fetch("offers", input, Duration.ofMinutes(5), () -> {
                    var data = collector.read("offers", input);
                    observations.save(data); // Actual timestamped quotes, never a cache replay presented as a new observation.
                    return data;
                });
            }
        } catch (RuntimeException ignored) { /* Next configured tick or explicit user request can recover. */ }
    }
}
