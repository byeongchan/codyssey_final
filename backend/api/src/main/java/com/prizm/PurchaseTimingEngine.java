package com.prizm;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Deterministic decision source. All thresholds are provisional, versioned configuration. */
public final class PurchaseTimingEngine {
  private static final JsonNode CONFIG;

  static {
    try (var stream =
        PurchaseTimingEngine.class.getResourceAsStream("/purchase-timing-policy.json")) {
      CONFIG = new ObjectMapper().readTree(Objects.requireNonNull(stream));
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private PurchaseTimingEngine() {}

  public static JsonNode configuration() {
    return CONFIG.deepCopy();
  }

  /** Limited advice about the observed price level, never a claim of equivalent checkout totals. */
  public static Map<String, Object> withPeriodFallback(
      Map<String, Object> strict,
      Map<String, Object> period,
      boolean historyFresh,
      LocalDate today) {
    if (!"INSUFFICIENT_DATA".equals(strict.get("decision"))) return strict;
    var out = new LinkedHashMap<>(strict);
    out.put("confidence", "LIMITED");
    var config = CONFIG.path("periodFallback");
    out.put("periodThresholds", config.deepCopy());
    String unit = Objects.toString(period.get("granularity"), "none");
    if (unit.startsWith("daily_")) unit = "daily";
    if (!Set.of("daily", "weekly", "monthly").contains(unit)
        || !historyFresh
        || !(period.get("sampleCount") instanceof Number count)
        || count.intValue() < config.path("minSamples").asInt()
        || !(period.get("latestPeriodLowest") instanceof Number latest)
        || !(period.get("median") instanceof Number median)) return out;
    try {
      LocalDate end =
          "monthly".equals(unit)
              ? YearMonth.parse(period.get("coverageEnd").toString()).atEndOfMonth()
              : LocalDate.parse(period.get("coverageEnd").toString());
      if (end.isBefore(today.minusDays(config.path(unit + "MaxAgeDays").asInt()))) return out;
    } catch (Exception ex) {
      return out;
    }
    if (latest.doubleValue() <= 0 || median.doubleValue() <= 0) return out;
    var p = (JsonNode) strict.get("thresholds");
    double gap = (latest.doubleValue() / median.doubleValue() - 1) * 100;
    double change = period.get("periodChangePercent") instanceof Number n ? n.doubleValue() : 0;
    String label = Map.of("daily", "일별", "weekly", "주간", "monthly", "월간").get(unit);
    out.put("assessmentBasis", "PERIOD_PRICE_LEVEL");
    out.put("referencePrice", latest);
    out.put("referencePriceKind", "LATEST_PERIOD_MINIMUM");
    out.put("data_limitations", List.of("자료 부족"));
    out.put(
        "risks",
        List.of(
            "기간별 최저가 흐름에 따른 참고 판단입니다. 현재 결제 금액·옵션·배송비는 구매 전에 확인해 주세요.",
            "과거 가격 흐름은 앞으로의 할인이나 가격을 보장하지 않습니다."));
    var reasons = new ArrayList<String>();
    out.put("reasoning", reasons);
    reasons.add(
        String.format(
            Locale.ROOT,
            "최근 %s 최저가는 %.0f원으로, 제공된 기간 표본 중앙값 %.0f원 대비 %.2f%%입니다.",
            label,
            latest.doubleValue(),
            median.doubleValue(),
            gap));
    if (gap >= p.path("highPct").asDouble()
        && change <= -config.path("fallingPercent").asDouble()
        && !"URGENT".equals(strict.get("urgency"))) {
      out.put("decision", "WAIT");
      out.put("headline", "기간별 가격 하락과 높은 최근 가격 수준을 근거로 잠시 대기를 고려하세요.");
      out.put(
          "wait_condition",
          "선택한 대기 한도 안에 다음 가격 갱신을 확인하고, 기간 표본 중앙값 수준으로 내려오는지 재검토하세요. 할인 일정은 미확인입니다.");
      reasons.add(
          String.format(
              Locale.ROOT, "첫 표본 대비 마지막 가격은 %.2f%% 변했으며, 마지막 가격은 아직 기간 중앙값보다 높습니다.", change));
    } else {
      out.put("decision", "BUY");
      out.put(
          "headline",
          gap <= p.path("reasonable90Pct").asDouble()
              ? "기간별 가격 흐름을 기준으로 구매를 고려할 만합니다."
              : "선택한 구매 시급성을 고려해 필요하다면 구매를 고려하세요.");
      out.put("wait_condition", null);
      reasons.add("선택한 구매 시급성과 기간별 가격 수준을 함께 고려한 참고 판단입니다.");
    }
    return out;
  }

  private static double median(List<Double> values) {
    var sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 0 ? sorted.get(n / 2 - 1) / 2 + sorted.get(n / 2) / 2 : sorted.get(n / 2);
  }

  public static Map<String, Object> evaluate(
      String productId,
      List<PriceAgentPolicy.Quote> input,
      Long current,
      boolean comparable,
      boolean fresh,
      String urgency,
      LocalDate today) {
    String profile = CONFIG.path("productProfiles").path(productId).asText("default");
    var p = CONFIG.path("profiles").path(profile);
    if (p.isMissingNode()) {
      profile = "default";
      p = CONFIG.path("profiles").path(profile);
    }
    int horizon = CONFIG.path("urgencyDays").path(urgency).asInt(14);
    var prices = new TreeMap<LocalDate, Long>();
    for (var q : input)
      if (q.price() > 0
          && q.price() <= 2 * DataPolicy.MAX_AMOUNT
          && !q.date().isAfter(today)
          && !q.date().isBefore(today.minusDays(89))) prices.merge(q.date(), q.price(), Math::min);
    var recent =
        prices.entrySet().stream().filter(e -> !e.getKey().isBefore(today.minusDays(29))).toList();
    var out = new LinkedHashMap<String, Object>();
    out.put("decision", "INSUFFICIENT_DATA");
    out.put("headline", "판단에 필요한 비교 가능한 근거가 부족합니다.");
    out.put("wait_condition", null);
    out.put("profile", profile);
    out.put("urgency", urgency);
    out.put("waitHorizonDays", horizon);
    out.put("policyVersion", CONFIG.path("version").asText());
    out.put("thresholds", p.deepCopy());
    out.put("risks", List.of("과거 가격과 할인 반복은 미래 가격이나 다음 할인을 보장하지 않습니다."));
    var limits = new ArrayList<String>();
    var reasons = new ArrayList<String>();
    out.put("data_limitations", limits);
    out.put("reasoning", reasons);
    if (!comparable) limits.add("상품 옵션·상태·판매 조건 및 배송 포함 가격 기준의 동일성이 검증되지 않았습니다.");
    if (!fresh || current == null || current <= 0) limits.add("최신의 유효한 비교 현재가가 없습니다.");
    boolean quality =
        recent.size() >= p.path("min30").asInt()
            && prices.size() >= p.path("min90").asInt()
            && ChronoUnit.DAYS.between(prices.firstKey(), prices.lastKey())
                >= p.path("minSpan").asInt()
            && ChronoUnit.DAYS.between(prices.lastKey(), today) <= p.path("maxAgeDays").asInt();
    if (!quality) limits.add("일별 관측 수·범위·최근성이 정책 기준에 미달합니다. 주·월 최저가로 일별 표본을 만들지 않습니다.");
    if (!quality) {
      reasons.add("최근 가격대와 반복 할인 패턴을 신뢰하기 어렵습니다.");
      return out;
    }
    double m30 = median(recent.stream().map(e -> e.getValue().doubleValue()).toList());
    double m90 = median(prices.values().stream().map(Long::doubleValue).toList());
    out.put("median30", m30);
    out.put("median90", m90);
    double meanX =
        prices.keySet().stream()
            .mapToLong(d -> ChronoUnit.DAYS.between(prices.firstKey(), d))
            .average()
            .orElse(0);
    double meanY = prices.values().stream().mapToDouble(v -> Math.log(v)).average().orElse(0),
        xy = 0,
        xx = 0;
    for (var e : prices.entrySet()) {
      double x = ChronoUnit.DAYS.between(prices.firstKey(), e.getKey()) - meanX;
      xy += x * (Math.log(e.getValue()) - meanY);
      xx += x * x;
    }
    out.put("trend30EquivalentPercent", (Math.exp(30 * xy / xx) - 1) * 100);
    var returns = new ArrayList<Double>();
    for (int i = 1; i < recent.size(); i++)
      if (recent.get(i - 1).getKey().plusDays(1).equals(recent.get(i).getKey()))
        returns.add((recent.get(i).getValue() / (double) recent.get(i - 1).getValue() - 1) * 100);
    Double volatility = null;
    if (returns.size() >= p.path("minReturns").asInt()) {
      double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      volatility =
          Math.sqrt(
              returns.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum()
                  / (returns.size() - 1));
    }
    out.put("volatility30Percent", volatility);
    var starts = new ArrayList<LocalDate>();
    var lows = new ArrayList<Double>();
    LocalDate previous = null, start = null;
    double low = Double.MAX_VALUE;
    boolean recovered = false;
    double cutoff = m90 * (1 - p.path("discountPct").asDouble() / 100);
    for (var e : prices.entrySet()) {
      if (previous == null || !previous.plusDays(1).equals(e.getKey())) {
        start = null;
        low = Double.MAX_VALUE;
        recovered = false;
      }
      if (e.getValue() <= cutoff) {
        if (start == null && recovered) start = e.getKey();
        if (start != null) low = Math.min(low, e.getValue());
      } else {
        if (start != null) {
          starts.add(start);
          lows.add(low);
          start = null;
          low = Double.MAX_VALUE;
        }
        recovered = true;
      }
      previous = e.getKey();
    }
    out.put("completedDiscountEpisodes", starts.size());
    out.put(
        "observedDiscountDaysPercent",
        prices.values().stream().filter(v -> v <= cutoff).count() * 100.0 / prices.size());
    var intervals = new ArrayList<Long>();
    for (int i = 1; i < starts.size(); i++)
      intervals.add(ChronoUnit.DAYS.between(starts.get(i - 1), starts.get(i)));
    long maxGap = intervals.stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE),
        minGap = intervals.stream().mapToLong(Long::longValue).min().orElse(0);
    Double discountMedian = lows.isEmpty() ? null : median(lows);
    out.put("observedDiscountPriceMedian", discountMedian);
    out.put(
        "observedDiscountDepthPercent",
        discountMedian == null ? null : (1 - discountMedian / m90) * 100);
    out.put("observedMaxIntervalDays", intervals.isEmpty() ? null : maxGap);
    if (!comparable || !fresh || current == null || current <= 0) {
      reasons.add("통계는 참고할 수 있지만 현재가와 같은 조건인지 확인해야 합니다.");
      return out;
    }
    double gap30 = (current / m30 - 1) * 100, gap90 = (current / m90 - 1) * 100;
    out.put("gapMedian30Percent", gap30);
    out.put("gapMedian90Percent", gap90);
    reasons.add(
        String.format(Locale.ROOT, "현재가는 30일 중앙값 대비 %.2f%%, 90일 중앙값 대비 %.2f%%입니다.", gap30, gap90));
    boolean repeated =
        starts.size() >= p.path("minEpisodes").asInt()
            && minGap > 0
            && maxGap / (double) minGap <= p.path("maxIntervalRatio").asDouble()
            && maxGap <= horizon
            && ChronoUnit.DAYS.between(starts.get(starts.size() - 1), today) <= maxGap;
    boolean benefit =
        discountMedian != null
            && (1 - discountMedian / current) * 100 >= p.path("minBenefitPct").asDouble();
    if (gap30 >= p.path("highPct").asDouble()
        && gap90 >= p.path("highPct").asDouble()
        && repeated
        && benefit) {
      out.put("decision", "WAIT");
      out.put("headline", "높은 현재가와 반복 할인 관측을 근거로 조건부 대기를 권합니다.");
      out.put(
          "wait_condition",
          String.format(
              Locale.ROOT,
              "최대 %d일 안에 동일 조건 총액이 과거 할인 중앙값 %.0f원 수준으로 내려오는지 확인하고, 기한 도달 시 재평가하세요. 할인 예정일은 확인되지 않았습니다.",
              horizon,
              discountMedian));
      reasons.add("회복을 사이에 둔 완료 할인 구간이 반복 관측되었으며 과거 간격이 선택한 대기 한도 이내입니다.");
    } else if (gap30 <= p.path("reasonable30Pct").asDouble()
        && gap90 <= p.path("reasonable90Pct").asDouble()) {
      out.put("decision", "BUY");
      out.put("headline", "현재 가격은 최근 가격대에서 합리적입니다.");
      reasons.add("역대 최저가 여부만으로 구매를 미룰 이유는 없습니다.");
    } else {
      limits.add("가격 자료는 있지만 합리적 구매 또는 실익 있는 대기를 뒷받침할 근거가 충분하지 않습니다.");
      reasons.add("최근보다 비싸다는 이유만으로 WAIT를 반환하지 않습니다.");
    }
    return out;
  }
}
