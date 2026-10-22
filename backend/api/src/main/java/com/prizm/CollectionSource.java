package com.prizm;

import java.util.List;

public interface CollectionSource {
    record Query(String q, String categoryId, String scenario, String mode) {
        public Query(String q, String categoryId, String scenario) { this(q, categoryId, scenario, "READ_ONLY"); }
    }
    record Result(List<String> productIds, String dataVersion, String referenceTime) {}
    final class Failure extends RuntimeException {
        final String code; final boolean retryable;
        public Failure(String code, String message, boolean retryable) { super(message); this.code = code; this.retryable = retryable; }
    }
    Result collect(Query query);
}
