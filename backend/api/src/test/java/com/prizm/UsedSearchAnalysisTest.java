package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsedSearchAnalysisTest {
  final ObjectMapper mapper = new ObjectMapper();

  JsonNode row(String id, long price, String status) {
    return mapper.valueToTree(
        Map.of(
            "provider",
            "karrot",
            "productId",
            id,
            "title",
            "검색 상품 " + id,
            "price",
            price,
            "status",
            status));
  }

  @Test
  void clearLowPriceClusterDoesNotDistortTheStatistics() {
    var result =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                row("a", 10, "on_sale"),
                row("b", 100, "on_sale"),
                row("c", 50000, "on_sale"),
                row("d", 50000, "on_sale"),
                row("e", 100000, "on_sale")));
    assertEquals(2, result.path("lowPriceExcludedCount").asInt());
    assertEquals(3, result.path("analyzedCount").asInt());
    assertEquals(50000, result.path("low").asLong());
    assertEquals(100000, result.path("high").asLong());
    assertEquals(66667, result.path("average").asLong());
    int distributed = 0;
    for (var bin : result.path("distribution")) distributed += bin.path("count").asInt();
    assertEquals(3, distributed);
  }

  @Test
  void genuinelyCheapResultsAndInsufficientReferencesAreNotFlaggedAsFake() {
    var cheap =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                row("a", 10, "on_sale"),
                row("b", 100, "on_sale"),
                row("c", 100, "on_sale"),
                row("d", 200, "on_sale"),
                row("e", 300, "on_sale")));
    assertEquals(0, cheap.path("lowPriceExcludedCount").asInt());
    assertEquals(10, cheap.path("low").asLong());
    var sparse =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                row("a", 10, "on_sale"), row("b", 50000, "on_sale"), row("c", 100000, "on_sale")));
    assertEquals(0, sparse.path("lowPriceExcludedCount").asInt());
  }

  @Test
  void duplicatesFreeAndCompletedTradesAreExcludedAndUnknownStatusIsExplicit() {
    var a = row("a", 50000, "unknown");
    var result =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                a,
                a,
                row("b", 0, "on_sale"),
                row("c", 10000, "sold"),
                row("d", 20000, "reserved")));
    assertEquals(4, result.path("candidateCount").asInt());
    assertEquals(3, result.path("excludedCount").asInt());
    assertEquals(1, result.path("unknownStatusCount").asInt());
    assertFalse(result.has("average"));
  }

  @Test
  void tiedPriceBinsAreAllMarkedInsteadOfInventingOneDominantRange() {
    var result =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                row("a", 50000, "on_sale"),
                row("b", 60000, "on_sale"),
                row("c", 100000, "on_sale"),
                row("d", 150000, "on_sale")));
    int dominant = 0;
    for (var bin : result.path("distribution")) if (bin.path("dominant").asBoolean()) dominant++;
    assertEquals(2, dominant);
  }

  @Test
  void regionCoverageIncludesListingsExcludedFromPriceStatistics() {
    var free =
        mapper
            .createObjectNode()
            .put("provider", "joongna")
            .put("productId", "free")
            .put("price", 0)
            .putNull("regionName");
    var result =
        UsedSearchAnalysis.assess(
            mapper,
            List.of(
                free,
                row("a", 50000, "on_sale"),
                row("b", 60000, "on_sale"),
                row("c", 100000, "on_sale")));
    assertEquals(3, result.path("analyzedCount").asInt());
    assertEquals(4, result.path("regionSummary").path("total").asInt());
    assertEquals(1, result.path("regionSummary").path("platforms").get(0).path("unknown").asInt());
  }
}
