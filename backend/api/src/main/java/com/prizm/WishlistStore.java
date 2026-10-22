package com.prizm;

import java.util.List;

public interface WishlistStore {
    record Entry(String productId, String createdAt) {}
    record Page(List<Entry> items, String nextCursor) {}
    Page list(String uid, String cursor, int limit);
    boolean contains(String uid, String productId);
    void save(String uid, String productId);
    void delete(String uid, String productId);
}
