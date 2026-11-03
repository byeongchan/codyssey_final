package com.prizm;

import java.time.Duration;
import java.util.*;

/** Legacy helper, intentionally not registered as a bean. Browsing never schedules refreshes. */
public class LiveRefreshScheduler {
  private final LiveDataCache cache;
  private final DanawaClient collector;
  private final DanawaObservations observations;

  public LiveRefreshScheduler(
      LiveDataCache cache, DanawaClient collector, DanawaObservations observations) {
    this.cache = cache;
    this.collector = collector;
    this.observations = observations;
  }

  public void refresh() {
    try {
      int count = 0;
      for (var item : cache.catalogue("").path("items")) {
        if (++count > 5) break;
        String id = item.path("productId").asText();
        if (!id.matches("[0-9]{1,30}")) continue;
        var input = Map.<String, Object>of("productId", id, "page", 1);
        cache.fetch(
            "offers",
            input,
            Duration.ofMinutes(5),
            () -> {
              var data = collector.read("offers", input);
              observations.save(
                  data); // Actual timestamped quotes, never a cache replay presented as a new
              // observation.
              return data;
            });
      }
    } catch (RuntimeException ignored) {
      /* Next configured tick or explicit user request can recover. */
    }
  }
}
