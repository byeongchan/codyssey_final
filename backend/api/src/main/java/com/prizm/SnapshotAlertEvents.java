package com.prizm;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Durable page checkpoints; expired leases are retried with idempotent alert evaluation. */
@Repository
public class SnapshotAlertEvents {
    public record Claim(String id, String token, Set<String> productIds, String cursor) {}
    private final FirebaseAccess firebase;
    public SnapshotAlertEvents(FirebaseAccess firebase) { this.firebase = firebase; }
    private Timestamp timestamp(Instant time) { return Timestamp.ofTimeSecondsAndNanos(time.getEpochSecond(), time.getNano()); }
    public Claim claim() {
        var db = firebase.firestore();
        var now = Instant.now();
        var candidates = PersonalData.await(db.collection("snapshotAlertEvents")
            .whereLessThanOrEqualTo("availableAt", timestamp(now)).orderBy("availableAt").limit(1).get()).getDocuments();
        if (candidates.isEmpty()) return null;
        var ref = candidates.get(0).getReference();
        String token = UUID.randomUUID().toString();
        return PersonalData.await(db.runTransaction(tx -> {
            var doc = tx.get(ref).get();
            if (!doc.exists()) return null;
            var available = doc.getTimestamp("availableAt");
            if (available == null || available.compareTo(timestamp(now)) > 0) return null;
            Object raw = doc.get("productIds");
            if (!(raw instanceof List<?> ids) || ids.size() > 20 || ids.stream().anyMatch(id -> !(id instanceof String)))
                throw new IllegalStateException("Invalid snapshot alert event");
            Set<String> products = new HashSet<>();
            for (Object id : ids) products.add((String) id);
            tx.update(ref, Map.of("token", token, "status", "PROCESSING", "availableAt", timestamp(now.plusSeconds(180))));
            return new Claim(ref.getId(), token, Set.copyOf(products), Objects.toString(doc.getString("cursor"), ""));
        }));
    }
    public void checkpoint(Claim claim, String nextCursor) { finish(claim, nextCursor, false); }
    public void retry(Claim claim) { finish(claim, claim.cursor(), true); }
    private void finish(Claim claim, String cursor, boolean retry) {
        var ref = firebase.firestore().collection("snapshotAlertEvents").document(claim.id());
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            var doc = tx.get(ref).get();
            if (!doc.exists() || !claim.token().equals(doc.getString("token"))) return false;
            boolean done = !retry && cursor.isEmpty();
            Map<String, Object> changes = new HashMap<>();
            changes.put("cursor", cursor); changes.put("status", done ? "DONE" : retry ? "RETRYING" : "PENDING");
            changes.put("token", FieldValue.delete()); changes.put("updatedAt", FieldValue.serverTimestamp());
            changes.put("availableAt", done ? FieldValue.delete() : timestamp(Instant.now().plusSeconds(retry ? 60 : 0)));
            tx.update(ref, changes); return true;
        }));
    }
}
