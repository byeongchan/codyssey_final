package com.prizm;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Repository
public class FirestoreAlertStore implements AlertStore {
    private final FirebaseAccess firebase;
    public FirestoreAlertStore(FirebaseAccess firebase) { this.firebase = firebase; }
    private CollectionReference alerts(String uid) { return firebase.firestore().collection("users").document(uid).collection("alerts"); }
    private CollectionReference notices(String uid) { return firebase.firestore().collection("users").document(uid).collection("notifications"); }
    private <T> T await(ApiFuture<T> future) {
        try { return future.get(8, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (TimeoutException ex) { future.cancel(true); throw unavailable(); }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            while (cause != null) { if (cause instanceof ResponseStatusException status) throw status; cause = cause.getCause(); }
            throw unavailable();
        }
    }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "알림 저장소를 확인하고 다시 시도해 주세요"); }
    private String timestamp(DocumentSnapshot doc, String field) { return doc.getTimestamp(field) == null ? null : doc.getTimestamp(field).toDate().toInstant().toString(); }
    private Alert alert(DocumentSnapshot doc) {
        return new Alert(doc.getId(), doc.getString("productId"), doc.getLong("targetPrice"), Boolean.TRUE.equals(doc.getBoolean("enabled")),
                timestamp(doc, "createdAt"), timestamp(doc, "updatedAt"), Boolean.TRUE.equals(doc.getBoolean("lastConditionMet")), timestamp(doc, "lastNotifiedAt"));
    }
    public Alert get(String uid, String id) {
        var doc = await(alerts(uid).document(id).get());
        if (!doc.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "가격 알림 설정이 없습니다");
        return alert(doc);
    }
    public Page<Alert> list(String uid, String cursor, int limit) {
        Query query = alerts(uid).orderBy(FieldPath.documentId()).limit(limit + 1);
        if (!cursor.isBlank()) query = query.startAfter(cursor);
        var docs = await(query.get()).getDocuments();
        var items = docs.stream().limit(limit).map(this::alert).toList();
        return new Page<>(items, docs.size() > limit ? items.get(items.size() - 1).id() : null);
    }
    public void configure(String uid, String id, Long target, Boolean enabled, boolean mustExist) {
        var reference = alerts(uid).document(id);
        await(firebase.firestore().runTransaction(transaction -> {
            PersonalData.active(transaction, alerts(uid).getParent());
            var previous = transaction.get(reference).get();
            if (mustExist && !previous.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "가격 알림 설정이 없습니다");
            long effectiveTarget = target != null ? target : previous.getLong("targetPrice");
            boolean effectiveEnabled = enabled != null ? enabled : Boolean.TRUE.equals(previous.getBoolean("enabled"));
            if (previous.exists() && previous.getLong("targetPrice") == effectiveTarget && Boolean.valueOf(effectiveEnabled).equals(previous.getBoolean("enabled"))) return false;
            Map<String, Object> data = previous.exists() ? new LinkedHashMap<>(previous.getData()) : new LinkedHashMap<>();
            data.put("productId", id); data.put("targetPrice", effectiveTarget); data.put("enabled", effectiveEnabled);
            if (!previous.exists()) data.put("createdAt", FieldValue.serverTimestamp());
            data.put("updatedAt", FieldValue.serverTimestamp());
            data.put("revision", previous.exists() ? previous.getLong("revision") + 1 : 1L);
            data.put("lastConditionMet", false); data.put("lastObservedAt", null);
            // sequence는 문서 삭제·재등록 시에도 알림 ID가 겹치지 않도록 세대 ID와 함께 사용한다.
            if (!previous.exists()) { data.put("generation", UUID.randomUUID().toString()); data.put("sequence", 0L); }
            transaction.set(reference, data); return true;
        }));
    }
    public void delete(String uid, String id) { await(alerts(uid).document(id).delete()); }
    public boolean evaluate(String uid, String id, PriceObservations.Observation observation) {
        if (!observation.available()) return false;
        var reference = alerts(uid).document(id);
        return await(firebase.firestore().runTransaction(transaction -> {
            if (PersonalData.deleting(transaction, alerts(uid).getParent())) return false;
            var previous = transaction.get(reference).get();
            if (!previous.exists()) return false;
            String lastObserved = previous.getString("lastObservedAt");
            var decision = AlertPolicy.decide(Boolean.TRUE.equals(previous.getBoolean("enabled")), previous.getLong("targetPrice"),
                    Boolean.TRUE.equals(previous.getBoolean("lastConditionMet")), lastObserved == null ? null : Instant.parse(lastObserved), observation);
            if (!decision.accepted()) return false;
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("lastConditionMet", decision.conditionMet()); changes.put("lastObservedAt", observation.observedAt().toString());
            if (decision.notifyUser()) {
                long sequence = previous.getLong("sequence") + 1;
                String notificationId = previous.getString("generation") + "-" + sequence;
                Map<String, Object> notification = new LinkedHashMap<>();
                notification.put("productId", id); notification.put("alertId", id); notification.put("targetPrice", previous.getLong("targetPrice"));
                notification.put("observedPrice", observation.price()); notification.put("observedAt", observation.observedAt().toString());
                notification.put("createdAt", FieldValue.serverTimestamp()); notification.put("readAt", null); notification.put("synthetic", true);
                transaction.create(notices(uid).document(notificationId), notification);
                changes.put("sequence", sequence); changes.put("lastNotifiedAt", FieldValue.serverTimestamp());
            }
            transaction.update(reference, changes); return decision.notifyUser();
        }));
    }
    public Page<Notification> notifications(String uid, String cursor, int limit) {
        Query query = notices(uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(limit + 1);
        if (!cursor.isBlank()) {
            var start = await(notices(uid).document(cursor).get());
            if (!start.exists()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알림 조회 커서를 확인하세요");
            query = query.startAfter(start);
        }
        var docs = await(query.get()).getDocuments();
        var items = docs.stream().limit(limit).map(doc -> new Notification(doc.getId(), doc.getString("productId"), doc.getString("alertId"),
                doc.getLong("targetPrice"), doc.getLong("observedPrice"), doc.getString("observedAt"), timestamp(doc, "createdAt"), timestamp(doc, "readAt"), true)).toList();
        return new Page<>(items, docs.size() > limit ? items.get(items.size() - 1).id() : null);
    }
    public void markRead(String uid, String id) {
        var reference = notices(uid).document(id);
        await(firebase.firestore().runTransaction(transaction -> {
            var previous = transaction.get(reference).get();
            if (!previous.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "알림이 없습니다");
            if (previous.getTimestamp("readAt") == null) transaction.update(reference, "readAt", FieldValue.serverTimestamp());
            return true;
        }));
    }
    public Scan scanEnabled(String cursor) {
        return scanEnabled(cursor, 100);
    }
    public Scan scanEnabled(String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("scan limit");
        var db = firebase.firestore();
        Query query = db.collectionGroup("alerts").whereEqualTo("enabled", true)
            .orderBy(FieldPath.documentId()).limit(limit);
        // A reference value remains a valid cursor after account/alert deletion.
        if (!cursor.isBlank()) query = query.startAfter(db.document(cursor));
        var docs = await(query.get()).getDocuments();
        var items = docs.stream().filter(doc -> doc.getReference().getPath().matches("users/[^/]+/alerts/[^/]+"))
                .map(doc -> new OwnerAlert(doc.getReference().getParent().getParent().getId(), doc.getId())).toList();
        return new Scan(items, docs.size() == limit ? docs.get(docs.size() - 1).getReference().getPath() : "");
    }
}
