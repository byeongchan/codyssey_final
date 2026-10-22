package com.prizm;

import com.google.cloud.firestore.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Repository
public class FirestoreHistoryStore implements HistoryStore {
    private final FirebaseAccess firebase;
    public FirestoreHistoryStore(FirebaseAccess firebase) { this.firebase = firebase; }
    private DocumentReference root(String uid) { return firebase.firestore().collection("users").document(uid); }
    public Page list(String uid, String cursor, int limit) {
        var collection = root(uid).collection("history");
        Query query = collection.orderBy("viewedAt", Query.Direction.DESCENDING).limit(limit + 1);
        if (!cursor.isBlank()) {
            // Cursor carries the ordering values, so deleting/revisiting its product does not invalidate it.
            try {
                var parts = cursor.split("~", -1);
                if (parts.length != 3 || !parts[2].matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
                query = query.orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                    .startAfter(com.google.cloud.Timestamp.ofTimeSecondsAndNanos(Long.parseLong(parts[0]), Integer.parseInt(parts[1])), parts[2]);
            } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기록 조회 커서를 확인하세요"); }
        } else query = query.orderBy(FieldPath.documentId(), Query.Direction.DESCENDING);
        var docs = PersonalData.await(query.get()).getDocuments();
        var items = docs.stream().limit(limit).map(doc -> new Entry(doc.getId(), doc.getTimestamp("viewedAt").toDate().toInstant().toString())).toList();
        String next = null;
        if (docs.size() > limit) {
            var last = docs.get(limit - 1); var stamp = last.getTimestamp("viewedAt");
            next = stamp.getSeconds() + "~" + stamp.getNanos() + "~" + last.getId();
        }
        return new Page(items, next);
    }
    public void record(String uid, String id) {
        var root = root(uid);
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            PersonalData.active(tx, root);
            tx.set(root.collection("history").document(id), Map.of("productId", id, "viewedAt", FieldValue.serverTimestamp()));
            return true;
        }));
    }
    public void delete(String uid, String id) { PersonalData.await(root(uid).collection("history").document(id).delete()); }
}
