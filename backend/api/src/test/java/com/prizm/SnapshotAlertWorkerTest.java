package com.prizm;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

class SnapshotAlertWorkerTest {
    final SnapshotAlertEvents events = mock(SnapshotAlertEvents.class);
    final SnapshotCatalogRepository catalog = mock(SnapshotCatalogRepository.class);
    final AlertStore alerts = mock(AlertStore.class);
    final PriceObservations prices = mock(PriceObservations.class);
    final SnapshotAlertWorker worker = new SnapshotAlertWorker(events, catalog, alerts, prices);
    final SnapshotAlertEvents.Claim claim = new SnapshotAlertEvents.Claim("event", "token", Set.of("p"), "before");
    void setup() {
        when(catalog.mode()).thenReturn("snapshot"); when(events.claim()).thenReturn(claim);
        when(alerts.scanEnabled("before", 10)).thenReturn(new AlertStore.Scan(List.of(
            new AlertStore.OwnerAlert("alice", "p"), new AlertStore.OwnerAlert("bob", "other")), "next"));
    }
    @Test void fileModeDoesNotAccessFirebase() {
        when(catalog.mode()).thenReturn("fixture"); worker.check();
        verifyNoInteractions(events, alerts, prices);
    }
    @Test void evaluatesOnlySavedProductsAndCheckpointsAfterEvaluation() {
        setup(); var observation = new PriceObservations.Observation(100L, Instant.parse("2026-10-22T03:00:00Z"));
        when(prices.current("p")).thenReturn(observation); worker.check();
        var order = inOrder(catalog, alerts, events);
        order.verify(events).claim(); order.verify(catalog).refresh();
        order.verify(alerts).scanEnabled("before", 10);
        order.verify(alerts).evaluate("alice", "p", observation);
        order.verify(events).checkpoint(claim, "next");
        verify(alerts, never()).evaluate(eq("bob"), anyString(), any());
    }
    @Test void failedPageIsRetriedWithoutAdvancingCursor() {
        setup(); when(alerts.scanEnabled("before", 10)).thenThrow(new IllegalStateException());
        worker.check(); verify(events).retry(claim); verify(events, never()).checkpoint(any(), anyString());
    }
    @Test void refreshFailureDoesNotEvaluateOldView() {
        setup(); when(catalog.refresh()).thenThrow(new IllegalStateException());
        worker.check(); verify(events).retry(claim); verifyNoInteractions(alerts, prices);
    }
}
