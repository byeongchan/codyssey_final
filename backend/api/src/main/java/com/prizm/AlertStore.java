package com.prizm;

import java.util.List;

public interface AlertStore {
    record Alert(String id, String productId, long targetPrice, boolean enabled, String createdAt, String updatedAt, boolean lastConditionMet, String lastNotifiedAt) {}
    record Notification(String id, String productId, String alertId, long targetPrice, long observedPrice, String observedAt, String createdAt, String readAt, boolean synthetic) {}
    record Page<T>(List<T> items, String nextCursor) {}
    record OwnerAlert(String uid, String id) {}
    record Scan(List<OwnerAlert> items, String nextCursor) {}
    Alert get(String uid, String id);
    Page<Alert> list(String uid, String cursor, int limit);
    void configure(String uid, String id, Long target, Boolean enabled, boolean mustExist);
    void delete(String uid, String id);
    boolean evaluate(String uid, String id, PriceObservations.Observation observation);
    Page<Notification> notifications(String uid, String cursor, int limit);
    void markRead(String uid, String id);
    Scan scanEnabled(String cursor);
    Scan scanEnabled(String cursor, int limit);
}
