package com.prizm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseDecisionTest {
    @Test void keepsExactThresholdsAndNeutralBand() {
        assertEquals("BUY", PurchaseDecision.decide(9800L, 10000, 7, false).verdict());
        assertEquals("NEUTRAL", PurchaseDecision.decide(9801L, 10000, 7, false).verdict());
        assertEquals("NEUTRAL", PurchaseDecision.decide(10199L, 10000, 7, false).verdict());
        assertEquals("WAIT", PurchaseDecision.decide(10200L, 10000, 7, false).verdict());
    }
    @Test void insufficientOrStaleEvidenceCannotProducePurchaseAdvice() {
        assertEquals("STALE_EVIDENCE", PurchaseDecision.decide(100L, 1000, 30, true).reasonCode());
        assertEquals("NO_CURRENT_PRICE", PurchaseDecision.decide(null, 1000, 30, false).reasonCode());
        assertEquals("SHORT_HISTORY", PurchaseDecision.decide(100L, 1000, 6, false).reasonCode());
        assertEquals("NO_AVERAGE", PurchaseDecision.decide(100L, 0, 30, false).reasonCode());
        assertEquals("INSUFFICIENT_DATA", PurchaseDecision.decide(100L, Double.NaN, 30, false).verdict());
    }
}
