package com.prizm;

import java.time.*;
import java.util.*;

/** Pure retention plan. Missing timestamps are protected, never guessed. */
public final class SnapshotRetentionPolicy {
    public static final int SNAPSHOT_DAYS = 30, EVENT_DAYS = 7, MAX_DELETES = 100;
    public record Snapshot(String id, String productId, Instant createdAt, boolean latest) {}
    public record Event(String id, Set<String> productIds, String status, Instant updatedAt, boolean leased) {}
    public record Plan(List<String> snapshotIds, List<String> eventIds, int remainingCandidates) {}
    private SnapshotRetentionPolicy() {}
    public static Plan plan(Instant now, List<Snapshot> snapshots, List<Event> events) {
        Set<String> pendingProducts = new HashSet<>();
        for (var event : events) if (!"DONE".equals(event.status()) || event.leased()) pendingProducts.addAll(event.productIds());
        var oldSnapshots = snapshots.stream().filter(s -> !s.latest() && !pendingProducts.contains(s.productId())
            && s.createdAt() != null && s.createdAt().isBefore(now.minus(Duration.ofDays(SNAPSHOT_DAYS))))
            .map(Snapshot::id).sorted().toList();
        var oldEvents = events.stream().filter(e -> "DONE".equals(e.status()) && !e.leased()
            && e.updatedAt() != null && e.updatedAt().isBefore(now.minus(Duration.ofDays(EVENT_DAYS))))
            .map(Event::id).sorted().toList();
        var snapshotIds = oldSnapshots.stream().limit(MAX_DELETES).toList();
        var eventIds = oldEvents.stream().limit(MAX_DELETES - snapshotIds.size()).toList();
        return new Plan(snapshotIds, eventIds, oldSnapshots.size() + oldEvents.size() - snapshotIds.size() - eventIds.size());
    }
}
