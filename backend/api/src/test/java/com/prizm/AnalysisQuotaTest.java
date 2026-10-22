package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisQuotaTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference users = mock(CollectionReference.class);
    final DocumentReference root = mock(DocumentReference.class);
    final DocumentSnapshot doc = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final ProductCacheTest.Time clock = new ProductCacheTest.Time();
    final Map<String, Long> saved = new HashMap<>();
    AnalysisQuota quota;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("users")).thenReturn(users); when(users.document("alice")).thenReturn(root);
        when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(doc));
        when(doc.getLong(anyString())).thenAnswer(call -> saved.get(call.getArgument(0)));
        doAnswer(call -> { Map<String, Long> values = call.getArgument(1); saved.putAll(values); return tx; })
            .when(tx).set(eq(root), anyMap(), any(SetOptions.class));
        when(db.<Boolean>runTransaction(any())).thenAnswer(call -> {
            Transaction.Function<Boolean> callback = call.getArgument(0);
            try { return ApiFutures.immediateFuture(callback.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        quota = new AnalysisQuota(firebase, clock);
    }
    @Test void sixthRequestIsRejectedAndNewWindowStartsAtExactlySixtySeconds() {
        for (int i = 0; i < 5; i++) quota.consume("alice");
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> quota.consume("alice")).getStatusCode().value());
        assertEquals(5L, saved.get("analysisCount"));
        clock.now = clock.now.plusSeconds(60); quota.consume("alice"); assertEquals(1L, saved.get("analysisCount"));
    }
    @Test void newServiceUsesSavedCounterAndDeletionGuardBlocksWrites() {
        for (int i = 0; i < 5; i++) quota.consume("alice");
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> new AnalysisQuota(firebase, clock).consume("alice")).getStatusCode().value());
        when(doc.getString("deletionState")).thenReturn("DELETING"); clearInvocations(tx);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> quota.consume("alice")).getStatusCode().value());
        verify(tx, never()).set(eq(root), anyMap(), any(SetOptions.class));
    }
}
