package com.prizm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PeriodPriceHistoryTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void normalizedPeriodsPreserveNullsIdentityAndOriginalGranularity() throws Exception {
    var raw =
        mapper.readTree(
            "{\"productId\":\"123\",\"currency\":\"KRW\",\"weekly\":[{\"date\":\"2026-10-12\",\"lowestPrice\":100},{\"date\":\"2026-10-05\",\"lowestPrice\":null}],\"monthly\":[]}");
    var normalized = PeriodPriceHistory.normalized(mapper, raw, "123");
    assertEquals(0, normalized.path("daily").size());
    assertEquals(2, normalized.path("weekly").size());
    assertTrue(normalized.path("weekly").get(0).path("lowestPrice").isNull());
    assertThrows(
        IllegalArgumentException.class, () -> PeriodPriceHistory.normalized(mapper, raw, "999"));
  }

  @Test
  void fallbackUsesWeeklyThenMonthlyWithoutInventingDailyIndicators() throws Exception {
    var history =
        mapper.readTree(
            "{\"weekly\":[{\"date\":\"2026-10-05\",\"lowestPrice\":120},{\"date\":\"2026-10-12\",\"lowestPrice\":100}],\"monthly\":[{\"date\":\"2026-08\",\"lowestPrice\":150},{\"date\":\"2026-09\",\"lowestPrice\":140}]}");
    var weekly = PriceAgentPolicy.periodFallback(history, LocalDate.of(2026, 10, 26), null);
    assertEquals("weekly", weekly.get("granularity"));
    assertEquals(100L, weekly.get("lowest"));
    assertEquals(110.0, weekly.get("median"));
    assertEquals(false, weekly.get("canCompareCurrent"));
    assertNull(weekly.get("gapFromLowPercent"));
    assertFalse(weekly.containsKey("volatility30Percent"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) history).remove("weekly");
    assertEquals(
        "monthly",
        PriceAgentPolicy.periodFallback(history, LocalDate.of(2026, 10, 26), null)
            .get("granularity"));
  }

  @Test
  void dailyApiSamplesTakePriorityOnlyWhenEnoughAndFuturePointsAreExcluded() throws Exception {
    var history = mapper.createObjectNode();
    var daily = history.putArray("daily");
    for (int i = 1; i <= 7; i++)
      daily.addObject().put("date", String.format("2026-10-%02d", i)).put("lowestPrice", 100 + i);
    daily.addObject().put("date", "2026-12-01").put("lowestPrice", 1);
    var selected = PriceAgentPolicy.periodFallback(history, LocalDate.of(2026, 10, 26), 110L);
    assertEquals("daily", selected.get("granularity"));
    assertEquals(7, selected.get("sampleCount"));
    assertEquals(101L, selected.get("lowest"));
  }

  @Test
  void durableStorageUsesPeriodDocumentsAndDoesNotCreateDailyObservations() throws Exception {
    var db = mock(Firestore.class);
    var tx = mock(Transaction.class);
    var roots = mock(CollectionReference.class);
    var points = mock(CollectionReference.class);
    var root = mock(DocumentReference.class);
    var point = mock(DocumentReference.class);
    when(db.collection("livePriceHistories")).thenReturn(roots);
    when(roots.document("reef-danawa_123")).thenReturn(root);
    when(root.collection(anyString())).thenReturn(points);
    when(points.document(anyString())).thenReturn(point);
    var data =
        mapper.readTree(
            "{\"source\":\"reef-danawa\",\"synthetic\":false,\"productId\":\"123\",\"fetchedAt\":null,\"receivedAt\":\"2026-10-26T00:00:00Z\",\"priceHistory\":{\"productId\":\"123\",\"currency\":\"KRW\",\"weekly\":[{\"date\":\"2026-10-19\",\"lowestPrice\":100}],\"monthly\":[]}}");
    PeriodPriceHistory.write(tx, db, mapper, data, 123L);
    verify(tx)
        .set(
            eq(root),
            argThat(
                (Map<String, Object> value) ->
                    value.get("fetchedAt") == null
                        && value.get("historyJson").toString().contains("weekly")));
    verify(points).document("2026-10-19");
    verify(db, never()).collection("liveDanawaProducts");
  }
}
