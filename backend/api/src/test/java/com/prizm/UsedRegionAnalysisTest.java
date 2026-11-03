package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsedRegionAnalysisTest {
  final ObjectMapper mapper = new ObjectMapper();

  JsonNode row(String provider, String id, String region) {
    var row = mapper.createObjectNode().put("provider", provider).put("productId", id);
    if (region == null) row.putNull("regionName");
    else row.put("regionName", region);
    return row;
  }

  @Test
  void missingRegionsAreCountedPerPlatformWithoutInferringTheSearchOrigin() {
    var a = row("joongna", "a", null);
    var result =
        UsedRegionAnalysis.assess(
            mapper,
            List.of(
                a,
                a,
                row("joongna", "b", "지역 정보 미제공"),
                row("joongna", "c", "대전 서구 관저동"),
                row("karrot", "d", "관저동"),
                row("karrot", "e", " ")));
    assertEquals(5, result.path("total").asInt());
    assertEquals(3, result.path("unknown").asInt());
    assertEquals(60.0, result.path("unknownPercent").asDouble());
    var joongna = result.path("platforms").get(0);
    var karrot = result.path("platforms").get(1);
    assertEquals(3, joongna.path("total").asInt());
    assertEquals(2, joongna.path("unknown").asInt());
    assertEquals(66.7, joongna.path("unknownPercent").asDouble());
    assertEquals(50.0, karrot.path("unknownPercent").asDouble());
  }

  @Test
  void whitespaceIsNormalizedButDifferentAddressLabelsAreNotGeocodedOrMerged() {
    var result =
        UsedRegionAnalysis.assess(
            mapper,
            List.of(
                row("joongna", "a", "  대전   서구 관저동 "),
                row("karrot", "a", "대전 서구 관저동"),
                row("karrot", "b", "관저동")));
    assertEquals(3, result.path("total").asInt());
    assertEquals(2, result.path("distinctRegions").asInt());
    assertEquals(2, result.path("regions").get(0).path("count").asInt());
    assertEquals(66.7, result.path("regions").get(0).path("percent").asDouble());
  }

  @Test
  void emptyResultsAndRegionPreviewHaveExplicitDenominatorsAndRemainder() {
    var empty = UsedRegionAnalysis.assess(mapper, List.of());
    assertEquals(0, empty.path("unknownPercent").asDouble());
    assertEquals(0, empty.path("total").asInt());
    var rows = new ArrayList<JsonNode>();
    for (int i = 0; i < 25; i++) rows.add(row("joongna", "id" + i, "지역" + i));
    var result = UsedRegionAnalysis.assess(mapper, rows);
    assertEquals(20, result.path("regions").size());
    assertEquals(5, result.path("otherKnownCount").asInt());
    assertEquals(0, result.path("unknown").asInt());
  }
}
