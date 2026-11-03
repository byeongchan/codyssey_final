package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsedRobustStatisticsTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sampleThresholdAndZeroSpreadDoNotInventAScale() {
    assertEquals(
        "NOT_ENOUGH_REFERENCES",
        UsedRobustStatistics.signal(mapper, 10, List.of(50000L)).path("state").asText());
    assertEquals(
        "ZERO_SPREAD",
        UsedRobustStatistics.signal(mapper, 10, Collections.nCopies(10, 50000L))
            .path("state")
            .asText());
  }

  @Test
  void logMadFlagsAnExtremePriceUsingTheReferenceGroupOnly() {
    var references = new ArrayList<Long>();
    for (int i = 0; i < 10; i++) references.add(50000L + i * 1000);
    var result = UsedRobustStatistics.signal(mapper, 10, references);
    assertTrue(result.path("flagged").asBoolean());
    assertEquals("LOW", result.path("direction").asText());
    assertEquals("LOG_MAD", result.path("method").asText());
  }
}
