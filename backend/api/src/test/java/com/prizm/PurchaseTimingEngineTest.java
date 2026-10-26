package com.prizm;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseTimingEngineTest {
    final LocalDate today=LocalDate.of(2026,10,26);
    List<PriceAgentPolicy.Quote> series(boolean rising, boolean discounts) {
        var rows=new ArrayList<PriceAgentPolicy.Quote>();
        for(int i=0;i<90;i++) {
            long price=rising?10000+i*10:10000;
            if(discounts && (i==63 || i==75 || i==87)) price=8500;
            rows.add(new PriceAgentPolicy.Quote(today.minusDays(89-i),price));
        }
        return rows;
    }
    Map<String,Object> decide(List<PriceAgentPolicy.Quote> rows,long price,boolean comparable,String urgency) {
        return PurchaseTimingEngine.evaluate("123",rows,price,comparable,true,urgency,today);
    }
    @Test void caseAReasonableEvenAboveHistoricalLow() {
        var rows=series(false,false); rows.set(0,new PriceAgentPolicy.Quote(today.minusDays(89),8000));
        assertEquals("BUY",decide(rows,9500,true,"NORMAL").get("decision"));
    }
    @Test void caseBRisingButReasonable() {
        var result=decide(series(true,false),10890,true,"NORMAL");
        assertEquals("BUY",result.get("decision")); assertTrue((double)result.get("trend30EquivalentPercent")>0);
    }
    @Test void caseCHighAndActualRepeatedDiscounts() {
        var result=decide(series(false,true),12000,true,"NORMAL");
        assertEquals("WAIT",result.get("decision"));assertNotNull(result.get("wait_condition"));
        assertEquals(3,result.get("completedDiscountEpisodes"));
    }
    @Test void caseDInsufficientHistory() { assertEquals("INSUFFICIENT_DATA",decide(series(false,false).subList(86,90),9500,true,"NORMAL").get("decision")); }
    @Test void caseEConditionsDifferEvenWhenCheap() { assertEquals("INSUFFICIENT_DATA",decide(series(false,false),8000,false,"NORMAL").get("decision")); }
    @Test void highWithoutWaitingEvidenceIsNotWait() { assertEquals("INSUFFICIENT_DATA",decide(series(false,false),12000,true,"NORMAL").get("decision")); }
    @Test void urgentCannotWaitForTwelveDayHistoricalInterval() { assertEquals("INSUFFICIENT_DATA",decide(series(false,true),12000,true,"URGENT").get("decision")); }
    @Test void staleOrEmptyCannotBuy() {
        assertEquals("INSUFFICIENT_DATA",PurchaseTimingEngine.evaluate("123",List.of(),9500L,true,true,"NORMAL",today).get("decision"));
        assertEquals("INSUFFICIENT_DATA",PurchaseTimingEngine.evaluate("123",series(false,false),9500L,true,false,"NORMAL",today).get("decision"));
    }
    Map<String,Object> weekly(double last, double median, double change) {
        return Map.of("granularity","weekly","sampleCount",11,"coverageEnd",today.minusDays(6).toString(),
            "latestPeriodLowest",last,"median",median,"periodChangePercent",change);
    }
    @Test void weeklyEvidenceCanGiveLimitedAdviceWithoutDailyOrCurrentQuote() {
        var strict=PurchaseTimingEngine.evaluate("123",List.of(),null,false,false,"NORMAL",today);
        var result=PurchaseTimingEngine.withPeriodFallback(strict,weekly(51340,56850,-19.63),true,today);
        assertEquals("BUY",result.get("decision"));assertEquals("PERIOD_PRICE_LEVEL",result.get("assessmentBasis"));
        assertEquals("LATEST_PERIOD_MINIMUM",result.get("referencePriceKind"));assertFalse(result.containsKey("median30"));
        assertEquals(List.of("자료 부족"),result.get("data_limitations"));
    }
    @Test void fallingButStillHighPeriodPriceCanJustifyConditionalWait() {
        var strict=PurchaseTimingEngine.evaluate("123",List.of(),null,false,false,"NORMAL",today);
        var result=PurchaseTimingEngine.withPeriodFallback(strict,weekly(12000,10000,-10),true,today);
        assertEquals("WAIT",result.get("decision"));assertNotNull(result.get("wait_condition"));
    }
    @Test void missingOrStalePeriodHistoryDoesNotFabricateAdvice() {
        var strict=PurchaseTimingEngine.evaluate("123",List.of(),null,false,false,"NORMAL",today);
        assertEquals("INSUFFICIENT_DATA",PurchaseTimingEngine.withPeriodFallback(strict,Map.of(),true,today).get("decision"));
        assertEquals("INSUFFICIENT_DATA",PurchaseTimingEngine.withPeriodFallback(strict,weekly(5000,6000,-10),false,today).get("decision"));
    }
}
