package com.prizm;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.FieldValue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.concurrent.*;

@Repository
public class FirestoreWishlistStore implements WishlistStore {
    private final FirebaseAccess firebase;
    public FirestoreWishlistStore(FirebaseAccess firebase) { this.firebase = firebase; }
    private CollectionReference collection(String uid) { return firebase.firestore().collection("users").document(uid).collection("wishlist"); }
    private <T> T await(ApiFuture<T> future, boolean ignoreExisting) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (TimeoutException ex) { future.cancel(true); throw unavailable(); }
        catch (ExecutionException ex) {
            if (ignoreExisting && ex.getCause() instanceof AlreadyExistsException) return null;
            throw unavailable();
        }
    }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "찜 저장소를 확인하고 다시 시도해 주세요"); }
    public Page list(String uid, String cursor, int limit) {
        var query = collection(uid).orderBy(FieldPath.documentId()).limit(limit + 1);
        if (!cursor.isBlank()) query = query.startAfter(cursor);
        var documents = await(query.get(), false).getDocuments();
        var items = documents.stream().limit(limit).map(doc -> new Entry(doc.getId(),
                doc.getTimestamp("createdAt") == null ? null : doc.getTimestamp("createdAt").toDate().toInstant().toString())).toList();
        return new Page(items, documents.size() > limit ? items.get(items.size() - 1).productId() : null);
    }
    public boolean contains(String uid, String id) { return await(collection(uid).document(id).get(), false).exists(); }
    public void save(String uid, String id) {
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            PersonalData.active(tx, collection(uid).getParent());
            var reference = collection(uid).document(id);
            if (!tx.get(reference).get().exists())
                tx.create(reference, Map.of("productId", id, "createdAt", FieldValue.serverTimestamp()));
            return true;
        }));
    }
    public void delete(String uid, String id) { await(collection(uid).document(id).delete(), false); }
}
