package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirestoreSnapshotStoreTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference batches = mock(CollectionReference.class), snapshots = mock(CollectionReference.class);
    final DocumentReference receiptRef = mock(DocumentReference.class), snapshotRef = mock(DocumentReference.class);
    final DocumentSnapshot receipt = mock(DocumentSnapshot.class), snapshot = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final CollectionReference events = mock(CollectionReference.class);
    final DocumentReference eventRef = mock(DocumentReference.class);
    FirestoreSnapshotStore store;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("fixtureSnapshotBatches")).thenReturn(batches);
        when(db.collection("fixturePriceSnapshots")).thenReturn(snapshots); when(batches.document(anyString())).thenReturn(receiptRef);
        when(snapshots.document("content-id")).thenReturn(snapshotRef);
        when(receiptRef.getId()).thenReturn("receipt-id");
        when(db.collection("snapshotAlertEvents")).thenReturn(events);
        when(events.document("receipt-id")).thenReturn(eventRef);
        when(tx.get(receiptRef)).thenReturn(ApiFutures.immediateFuture(receipt)); when(tx.get(snapshotRef)).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(db.<SnapshotStore.Receipt>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<SnapshotStore.Receipt> function = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(function.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        store = new FirestoreSnapshotStore(firebase);
    }
    List<SnapshotStore.Snapshot> content() { return List.of(new SnapshotStore.Snapshot("content-id", "p", Map.of("source", "fixture"), 3, 7)); }
    @Test void receiptAndSnapshotAreWrittenInOneTransactionAfterAllReads() throws Exception {
        var result = store.commit("key", "version", "digest", content());
        assertFalse(result.duplicate()); assertEquals(1, result.newSnapshots()); assertEquals(3, result.offerCount());
        var order = inOrder(tx); order.verify(tx).get(receiptRef); order.verify(tx).get(snapshotRef);
        order.verify(tx).create(eq(snapshotRef), any()); order.verify(tx).create(eq(receiptRef), any());
        order.verify(tx).create(eq(eventRef), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(event ->
            List.of("p").equals(event.get("productIds")) && "PENDING".equals(event.get("status"))));
    }
    @Test void duplicateReceiptReturnsWithoutRewritingSnapshots() {
        when(receipt.exists()).thenReturn(true); when(receipt.getString("payloadDigest")).thenReturn("digest");
        when(receipt.getLong("snapshotCount")).thenReturn(1L); when(receipt.getLong("offerCount")).thenReturn(3L); when(receipt.getLong("dailyPriceCount")).thenReturn(7L);
        var result = store.commit("key", "version", "digest", content());
        assertTrue(result.duplicate()); assertEquals(0, result.newSnapshots());
        verify(tx, never()).create(any(DocumentReference.class), any());
    }
    @Test void reusedKeyWithDifferentPayloadIsConflictAndOverlappingBatchReusesContent() {
        when(receipt.exists()).thenReturn(true); when(receipt.getString("payloadDigest")).thenReturn("other");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.commit("key", "version", "digest", content())).getStatusCode().value());
        verify(tx, never()).create(any(DocumentReference.class), any());
        when(receipt.exists()).thenReturn(false); when(snapshot.exists()).thenReturn(true); when(snapshot.getString("contentDigest")).thenReturn("content-id");
        assertEquals(0, store.commit("other-key", "version", "digest", content()).newSnapshots());
        verify(tx, never()).create(eq(snapshotRef), any()); verify(tx).create(eq(receiptRef), any());
    }
}
