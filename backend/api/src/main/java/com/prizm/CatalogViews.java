package com.prizm;

import java.util.function.Supplier;

final class CatalogViews {
    private CatalogViews() {}
    static <T> T read(CatalogRepository repo, Supplier<T> action) {
        if (repo instanceof SnapshotCatalogRepository snapshots) try (var ignored = snapshots.scope()) { return action.get(); }
        return action.get();
    }
}
