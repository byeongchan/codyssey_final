package com.prizm;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AlertPolicyTest {
    private final Instant first = Instant.parse("2026-10-22T03:00:00Z");
    @Test void firstMatchAndReentryNotifyButRepeatedMatchingPricesDoNot() {
        var initial = AlertPolicy.decide(true, 400000, false, null, new PriceObservations.Observation(384900L, first));
        assertTrue(initial.notifyUser());
        var repeat = AlertPolicy.decide(true, 400000, true, first, new PriceObservations.Observation(384900L, first));
        assertFalse(repeat.accepted()); assertFalse(repeat.notifyUser());
        var stillMatching = AlertPolicy.decide(true, 400000, true, first, new PriceObservations.Observation(380000L, first.plusSeconds(1)));
        assertTrue(stillMatching.conditionMet()); assertFalse(stillMatching.notifyUser());
        var above = AlertPolicy.decide(true, 400000, true, first, new PriceObservations.Observation(410000L, first.plusSeconds(2)));
        assertFalse(above.conditionMet()); assertFalse(above.notifyUser());
        var returns = AlertPolicy.decide(true, 400000, above.conditionMet(), first.plusSeconds(2), new PriceObservations.Observation(390000L, first.plusSeconds(3)));
        assertTrue(returns.notifyUser());
    }
    @Test void unavailableDataDoesNotResetAMatchedCondition() {
        var result = AlertPolicy.decide(true, 400000, true, first, new PriceObservations.Observation(null, null));
        assertFalse(result.accepted()); assertTrue(result.conditionMet()); assertFalse(result.notifyUser());
    }
    @Test void disabledOrOlderObservationsDoNotNotify() {
        assertFalse(AlertPolicy.decide(false, 400000, false, null, new PriceObservations.Observation(100L, first)).notifyUser());
        assertFalse(AlertPolicy.decide(true, 400000, false, first, new PriceObservations.Observation(100L, first.minusSeconds(1))).accepted());
        assertFalse(AlertPolicy.decide(true, 400000, false, null, new PriceObservations.Observation(-1L, first)).accepted());
    }
    @Test void exactTargetIsAMatchAndNewConfigurationMayMatchImmediately() {
        assertTrue(AlertPolicy.decide(true, 384900, false, null, new PriceObservations.Observation(384900L, first)).notifyUser());
        assertTrue(AlertPolicy.decide(true, 400000, false, null, new PriceObservations.Observation(384900L, first)).notifyUser());
    }
}
