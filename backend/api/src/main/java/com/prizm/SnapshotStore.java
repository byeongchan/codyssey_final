package com.prizm;

import java.util.List;
import java.util.Map;

public interface SnapshotStore {
    record Snapshot(String id, String productId, Map<String, Object> content, int offerCount, int dailyPriceCount) {}
    record Receipt(String idempotencyKey, boolean duplicate, int snapshotCount, int newSnapshots, int offerCount, int dailyPriceCount) {}
    Receipt commit(String key, String dataVersion, String payloadDigest, List<Snapshot> snapshots);
}
