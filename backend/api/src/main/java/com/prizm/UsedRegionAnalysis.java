package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Listing locations, not the search origin or a verified seller address. */
final class UsedRegionAnalysis {
  private static final Set<String> UNKNOWN =
      Set.of(
          "",
          "-",
          "미제공",
          "지역 정보 미제공",
          "지역정보미제공",
          "지역 미상",
          "미상",
          "정보 없음",
          "정보없음",
          "알 수 없음",
          "unknown",
          "n/a",
          "null");

  static String region(JsonNode row) {
    String value = row.path("regionName").asText("").strip().replaceAll("\\s+", " ");
    return UNKNOWN.contains(value.toLowerCase(Locale.ROOT)) ? "" : value;
  }

  static ObjectNode assess(ObjectMapper mapper, Collection<JsonNode> input) {
    var unique = new LinkedHashMap<String, JsonNode>();
    for (var row : input)
      unique.putIfAbsent(row.path("provider").asText() + ":" + row.path("productId").asText(), row);
    var result = counts(mapper, new ArrayList<>(unique.values()));
    var platforms = mapper.createArrayNode();
    for (String provider : List.of("joongna", "karrot")) {
      var rows =
          unique.values().stream()
              .filter(row -> provider.equals(row.path("provider").asText()))
              .toList();
      var platform = counts(mapper, rows);
      platform.put("provider", provider);
      platforms.add(platform);
    }
    result.set("platforms", platforms);
    result.put("basis", "중복 제거한 조회 매물 전체 · 가격 분석 제외 전 · 비율은 각 전체 건수 기준");
    result.put(
        "limitation",
        "매물에 표시된 지역명을 그대로 집계합니다. 검색 기준 동네·판매자 거주지·정확한 거리와 다를 수 있으며, 다른 전체 주소의 같은 동 이름이 합쳐질 수 있습니다. 미제공 지역은 추정하지 않습니다.");
    return result;
  }

  private static ObjectNode counts(ObjectMapper mapper, List<JsonNode> rows) {
    var counts = new TreeMap<String, Integer>();
    int unknown = 0;
    for (var row : rows) {
      String name = region(row);
      if (name.isEmpty()) unknown++;
      else counts.merge(name, 1, Integer::sum);
    }
    var result = mapper.createObjectNode();
    int total = rows.size();
    result.put("total", total);
    result.put("known", total - unknown);
    result.put("unknown", unknown);
    result.put("knownPercent", percent(total - unknown, total));
    result.put("unknownPercent", percent(unknown, total));
    result.put("distinctRegions", counts.size());
    var sorted =
        counts.entrySet().stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .toList();
    var regions = mapper.createArrayNode();
    int shown = 0;
    for (var entry : sorted.stream().limit(20).toList()) {
      var item = regions.addObject();
      item.put("name", entry.getKey());
      item.put("count", entry.getValue());
      item.put("percent", percent(entry.getValue(), total));
      shown += entry.getValue();
    }
    result.set("regions", regions);
    result.put("otherKnownCount", total - unknown - shown);
    return result;
  }

  private static double percent(int count, int total) {
    return total == 0 ? 0 : Math.round(count * 1000.0 / total) / 10.0;
  }
}
