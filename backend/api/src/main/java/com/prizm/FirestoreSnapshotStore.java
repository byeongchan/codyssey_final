package com.prizm;

import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class FirestoreSnapshotStore implements SnapshotStore {
    private final FirebaseAccess firebase;
    public FirestoreSnapshotStore(FirebaseAccess firebase) { this.firebase = firebase; }
    public Receipt commit(String key, String version, String digest, List<Snapshot> snapshots) {
        var db = firebase.firestore();
        var receiptRef = db.collection("fixtureSnapshotBatches").document(FixtureSnapshots.hash(key.getBytes(StandardCharsets.UTF_8)));
        return PersonalData.await(db.runTransaction(tx -> {
            var previous = tx.get(receiptRef).get();
            if (previous.exists()) {
                if (!digest.equals(previous.getString("payloadDigest"))) throw conflict();
                return new Receipt(key, true, Math.toIntExact(previous.getLong("snapshotCount")), 0,
                    Math.toIntExact(previous.getLong("offerCount")), Math.toIntExact(previous.getLong("dailyPriceCount")));
            }
            // Read all targets before writing. Overlapping batches contend on the same content-addressed documents.
            var existing = new ArrayList<DocumentSnapshot>();
            for (var snapshot : snapshots) existing.add(tx.get(db.collection("fixturePriceSnapshots").document(snapshot.id())).get());
            int created = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                var snapshot = snapshots.get(i); var old = existing.get(i);
                if (old.exists()) {
                    if (!snapshot.id().equals(old.getString("contentDigest"))) throw conflict();
                    continue;
                }
                tx.create(db.collection("fixturePriceSnapshots").document(snapshot.id()), Map.of("schemaVersion", 1,
                    "productId", snapshot.productId(), "contentDigest", snapshot.id(), "sourceDataVersion", version,
                    "content", snapshot.content(), "createdAt", FieldValue.serverTimestamp()));
                created++;
            }
            int offerCount = snapshots.stream().mapToInt(Snapshot::offerCount).sum();
            int dailyCount = snapshots.stream().mapToInt(Snapshot::dailyPriceCount).sum();
            var data = new LinkedHashMap<String, Object>();
            data.put("schemaVersion", 1); data.put("idempotencyKey", key); data.put("payloadDigest", digest); data.put("dataVersion", version);
            data.put("snapshotIds", snapshots.stream().map(Snapshot::id).toList()); data.put("snapshotCount", snapshots.size());
            data.put("offerCount", offerCount); data.put("dailyPriceCount", dailyCount); data.put("createdAt", FieldValue.serverTimestamp());
            tx.create(receiptRef, data);
            // Atomic outbox: a committed batch always has a recoverable evaluation event.
            tx.create(db.collection("snapshotAlertEvents").document(receiptRef.getId()), Map.of(
                "productIds", snapshots.stream().map(Snapshot::productId).distinct().toList(),
                "cursor", "", "status", "PENDING", "availableAt", com.google.cloud.Timestamp.now(),
                "createdAt", FieldValue.serverTimestamp()));
            return new Receipt(key, false, snapshots.size(), created, offerCount, dailyCount);
        }));
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "같은 스냅샷 키에 다른 근거가 저장되어 있습니다"); }
}
