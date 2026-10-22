package com.prizm;

import java.time.Instant;

/** 불명확한 가격은 미충족으로 간주하지 않는다. 재수집·역순 수집에서 기존 판정을 보존한다. */
final class AlertPolicy {
    record Decision(boolean accepted, boolean conditionMet, boolean notifyUser) {}
    static Decision decide(boolean enabled, long target, boolean previousMet, Instant lastObservedAt, PriceObservations.Observation observation) {
        if (!enabled || !observation.available() || (lastObservedAt != null && !observation.observedAt().isAfter(lastObservedAt)))
            return new Decision(false, previousMet, false);
        boolean met = observation.price() <= target;
        return new Decision(true, met, met && !previousMet);
    }
}
