package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PriceAgentPolicyTest {
  @Test
  void conversationalProductQuestionsAndFollowupsAreRecognized() {
    assertEquals("SPECS", PriceAgentPolicy.intent("이건 뭐야?", null));
    assertEquals("SPECS", PriceAgentPolicy.intent("게임용으로 괜찮아?", null));
    assertEquals("CHAT", PriceAgentPolicy.intent("안녕하세요", null));
    assertEquals("CHAT", PriceAgentPolicy.intent("이 상품 어떤 거야?", null));
    assertEquals("SPECS", PriceAgentPolicy.intent("설명해줘", null));
    assertEquals("SPECS", PriceAgentPolicy.intent("그게 무슨 말이야?", "SPECS"));
    assertEquals("TIMING", PriceAgentPolicy.intent("예산은 60만원이에요", null));
  }

  final LocalDate today = LocalDate.of(2026, 10, 26);

  @Test
  void metricsUseActualCalendarWindowsAndDoNotFillMissingDays() {
    var stats =
        PriceAgentPolicy.metrics(
            List.of(
                new PriceAgentPolicy.Quote(today, 90),
                new PriceAgentPolicy.Quote(today.minusDays(1), 100),
                new PriceAgentPolicy.Quote(today.minusDays(2), 110),
                new PriceAgentPolicy.Quote(today.minusDays(4), 120),
                new PriceAgentPolicy.Quote(today.minusDays(90), 1),
                new PriceAgentPolicy.Quote(today.plusDays(1), 1)),
            99L,
            today);
    assertEquals(90L, stats.get("lowest30"));
    assertEquals(105.0, stats.get("median90"));
    assertEquals(10.0, (double) stats.get("gapFromLowPercent"), .00001);
    assertEquals(2, stats.get("consecutiveReturnCount"));
    assertNotNull(stats.get("volatility30Percent"));
    assertNull(stats.get("discountFrequencyPercent"));
    assertEquals(4, stats.get("days90"));
  }

  @Test
  void discountFrequencyHasExplicitBaselineAndSeasonalityNeedsDifferentYears() {
    var rows = new ArrayList<PriceAgentPolicy.Quote>();
    for (int i = 0; i < 10; i++)
      rows.add(new PriceAgentPolicy.Quote(today.minusDays(i), i == 0 ? 9000 : 10000));
    assertEquals(
        10.0, PriceAgentPolicy.metrics(rows, 9000L, today).get("discountFrequencyPercent"));
    for (int year : List.of(2024, 2025))
      for (int day = 1; day <= 3; day++)
        rows.add(new PriceAgentPolicy.Quote(LocalDate.of(year, 10, day), 10000));
    assertEquals(true, PriceAgentPolicy.metrics(rows, 9000L, today).get("seasonalityAvailable"));
  }

  @Test
  void equivalentAndFollowupQuestionsRouteWithoutModelCalls() {
    assertEquals("TIMING", PriceAgentPolicy.intent("지금 사도 될까요?", null));
    assertEquals("TIMING", PriceAgentPolicy.intent("구매 추천해줘", null));
    assertEquals("TIMING", PriceAgentPolicy.intent("왜?", "TIMING"));
    assertEquals("CLARIFY", PriceAgentPolicy.intent("왜?", null));
    assertEquals("OFF_TOPIC", PriceAgentPolicy.intent("파이썬 코드를 작성해줘", null));
  }
}
