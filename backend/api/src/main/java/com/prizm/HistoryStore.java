package com.prizm;

import java.util.List;

public interface HistoryStore {
    record Entry(String productId, String viewedAt) {}
    record Page(List<Entry> items, String nextCursor) {}
    Page list(String uid, String cursor, int limit);
    void record(String uid, String id);
    void delete(String uid, String id);
}
