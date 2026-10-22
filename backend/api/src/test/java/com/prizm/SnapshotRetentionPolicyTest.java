package com.prizm;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRetentionPolicyTest {
    final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    final Instant old = now.minus(Duration.ofDays(40));
    @Test void protectsLatestPendingProductsMissingDatesAndBoundary() {
        var rows = List.of(
            new SnapshotRetentionPolicy.Snapshot("old", "p", old, false),
            new SnapshotRetentionPolicy.Snapshot("latest", "p", old, true),
            new SnapshotRetentionPolicy.Snapshot("pending", "q", old, false),
            new SnapshotRetentionPolicy.Snapshot("undated", "p", null, false),
            new SnapshotRetentionPolicy.Snapshot("boundary", "p", now.minus(Duration.ofDays(30)), false));
        var events = List.of(new SnapshotRetentionPolicy.Event("waiting", Set.of("q"), "RETRYING", old, false));
        var plan = SnapshotRetentionPolicy.plan(now, rows, events);
        assertEquals(List.of("old"), plan.snapshotIds()); assertTrue(plan.eventIds().isEmpty());
    }
    @Test void onlyUnleasedOldDoneEventsAreDeleted() {
        var events = List.of(new SnapshotRetentionPolicy.Event("done", Set.of(), "DONE", old, false),
            new SnapshotRetentionPolicy.Event("leased", Set.of("p"), "DONE", old, true),
            new SnapshotRetentionPolicy.Event("boundary", Set.of(), "DONE", now.minus(Duration.ofDays(7)), false),
            new SnapshotRetentionPolicy.Event("undated", Set.of(), "DONE", null, false),
            new SnapshotRetentionPolicy.Event("pending", Set.of(), "PENDING", old, false));
        assertEquals(List.of("done"), SnapshotRetentionPolicy.plan(now, List.of(), events).eventIds());
    }
    @Test void capsWholePlanAtOneHundredAndReportsRemaining() {
        var rows = new ArrayList<SnapshotRetentionPolicy.Snapshot>();
        for (int i = 0; i < 102; i++) rows.add(new SnapshotRetentionPolicy.Snapshot("s" + i, "p", old, false));
        var plan = SnapshotRetentionPolicy.plan(now, rows,
            List.of(new SnapshotRetentionPolicy.Event("done", Set.of(), "DONE", old, false)));
        assertEquals(100, plan.snapshotIds().size()); assertTrue(plan.eventIds().isEmpty()); assertEquals(3, plan.remainingCandidates());
    }
}
