package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveDataCacheTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference collection = mock(CollectionReference.class);
    final DocumentReference reference = mock(DocumentReference.class);
    final DocumentSnapshot document = mock(DocumentSnapshot.class);
    final Transaction transaction = mock(Transaction.class);
    final ObjectMapper mapper = new ObjectMapper();
    LiveDataCache cache;
    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("liveDisplayCache")).thenReturn(collection);
        when(collection.document(anyString())).thenReturn(reference); when(transaction.get(reference)).thenReturn(ApiFutures.immediateFuture(document));
        when(document.exists()).thenReturn(true);
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> function = invocation.getArgument(0);
            return ApiFutures.immediateFuture(function.updateCallback(transaction));
        });
        cache = new LiveDataCache(firebase, mapper);
    }
    @AfterEach void close() { cache.close(); }
    @Test void freshSharedResultDoesNotCallExternalLoaderOrWriteObservationCollections() throws Exception {
        when(document.getString("payloadJson")).thenReturn("{\"source\":\"reef-danawa\",\"synthetic\":false,\"fetchedAt\":\"2026-10-22T03:00:00Z\",\"items\":[]}");
        when(document.getString("status")).thenReturn("SUCCEEDED"); when(document.getLong("expiresAt")).thenReturn(System.currentTimeMillis() + 60000);
        Supplier<JsonNode> loader = mock(Supplier.class);
        var view = cache.fetch("search", Map.of("query", "SSD", "page", 1), Duration.ofMinutes(15), loader);
        assertNotNull(view.data()); assertEquals("2026-10-22T03:00:00Z", view.data().path("fetchedAt").asText());
        verifyNoInteractions(loader); verify(db, never()).collection("liveDanawaProducts");
        assertTrue((Boolean)((Map<?, ?>)((Map<?, ?>)cache.response(view).getBody()).get("meta")).get("stale"));
    }
    @Test void activeFirestoreLeaseIsSharedAcrossServiceInstances() {
        when(document.getString("status")).thenReturn("RUNNING"); when(document.getLong("leaseUntil")).thenReturn(System.currentTimeMillis() + 60000);
        Supplier<JsonNode> loader = mock(Supplier.class);
        var second = new LiveDataCache(firebase, mapper);
        try {
                var a = cache.fetch("product", Map.of("productId", "123"), Duration.ofHours(6), loader);
                var b = second.fetch("product", Map.of("productId", "123"), Duration.ofHours(6), loader);
                assertEquals(a.id(), b.id()); assertNull(a.data()); assertEquals(202, cache.response(a).getStatusCode().value());
                verifyNoInteractions(loader);
        } finally { second.close(); }
    }
    @Test void keyIncludesKindOptionsModelAndEvidenceButIgnoresMapInsertionOrder() {
        assertEquals(cache.key("search", Map.of("query", "SSD", "page", 1)), cache.key("search", new LinkedHashMap<>(Map.of("page", 1, "query", "SSD"))));
        assertNotEquals(cache.key("search", Map.of("query", "SSD", "page", 1)), cache.key("search", Map.of("query", "SSD", "page", 2)));
        assertNotEquals(cache.key("explanation", Map.of("model", "gemini-a", "dataVersion", "a")), cache.key("explanation", Map.of("model", "gemini-b", "dataVersion", "a")));
        assertNotEquals(cache.key("explanation", Map.of("model", "gemini-a", "dataVersion", "a")), cache.key("explanation", Map.of("model", "gemini-a", "dataVersion", "b")));
        assertEquals("SSD 2TB", LiveDataCache.normalize("  SSD   2TB  "));
    }
    @Test void sameEvidenceExplanationIsReusedEvenAfterDisplayTtl() {
        when(document.getString("payloadJson")).thenReturn("{\"model\":\"gemini-test\",\"synthetic\":false}");
        when(document.getString("status")).thenReturn("SUCCEEDED"); when(document.getLong("expiresAt")).thenReturn(1L);
        Supplier<JsonNode> loader = mock(Supplier.class);
        assertNotNull(cache.fetch("explanation", Map.of("productId", "123", "dataVersion", "a"), Duration.ofDays(30), loader).data());
        verifyNoInteractions(loader);
    }
    @Test void publicJobEndpointCannotReadExplanationJobs() {
        when(reference.get()).thenReturn(ApiFutures.immediateFuture(document)); when(document.getString("kind")).thenReturn("explanation");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> cache.job("a".repeat(64), false));
        assertEquals(404, error.getStatusCode().value());
    }
    @Test void storageErrorsDistinguishMissingIndexAndPermissions() {
        for (var status : List.of(io.grpc.Status.FAILED_PRECONDITION, io.grpc.Status.PERMISSION_DENIED, io.grpc.Status.UNAUTHENTICATED)) {
            when(reference.get()).thenReturn(ApiFutures.immediateFailedFuture(status.withDescription("private provider detail").asRuntimeException()));
            var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> cache.job("a".repeat(64), false));
            assertEquals(503, error.getStatusCode().value());
            String expected = status.getCode() == io.grpc.Status.Code.FAILED_PRECONDITION ? "인덱스"
                : status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED ? "권한" : "인증";
            assertTrue(error.getReason().contains(expected));
            assertFalse(error.getReason().contains("private provider detail"));
        }
    }
}
