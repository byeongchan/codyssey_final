package com.prizm;

import com.google.cloud.Timestamp;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Metadata only: no user collections, search terms, raw prices or event cursors. */
@Repository
public class OperationsStore {
    private final FirebaseAccess firebase;
    public OperationsStore(FirebaseAccess firebase) { this.firebase = firebase; }
    public Map<String, Object> status() {
        var db = firebase.firestore();
        return PersonalData.await(db.runTransaction(tx -> {
            var snapshots = tx.get(db.collection("fixturePriceSnapshots").select("createdAt").limit(501)).get().getDocuments();
            var receipts = tx.get(db.collection("fixtureSnapshotBatches").select("createdAt").limit(501)).get().getDocuments();
            var events = tx.get(db.collection("snapshotAlertEvents").select("status", "createdAt").limit(501)).get().getDocuments();
            var counts = new TreeMap<String, Integer>(); Instant oldest = null;
            for (var doc : events.stream().limit(500).toList()) {
                String raw = Objects.toString(doc.getString("status"), "UNKNOWN");
                String status = Set.of("PENDING", "PROCESSING", "RETRYING", "DONE").contains(raw) ? raw : "UNKNOWN";
                counts.merge(status, 1, Integer::sum);
                Timestamp created = doc.getTimestamp("createdAt");
                if (!"DONE".equals(status) && created != null) {
                    Instant time = Instant.ofEpochSecond(created.getSeconds(), created.getNanos());
                    if (oldest == null || time.isBefore(oldest)) oldest = time;
                }
            }
            var eventSummary = new LinkedHashMap<String, Object>();
            eventSummary.put("sampled", Math.min(events.size(), 500)); eventSummary.put("truncated", events.size() > 500);
            eventSummary.put("statuses", counts); eventSummary.put("oldestPendingAt", oldest == null ? null : oldest.toString());
            return Map.of("state", "AVAILABLE", "snapshots", count(snapshots.size()), "receipts", count(receipts.size()), "events", eventSummary);
        }));
    }
    private Map<String, Object> count(int size) { return Map.of("sampled", Math.min(size, 500), "truncated", size > 500); }
}
