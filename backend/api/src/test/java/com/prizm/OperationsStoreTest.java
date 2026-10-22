package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsStoreTest {
    @Test void readsOnlyBoundedMetadataAndMarksTruncatedCounts() throws Exception {
        var firebase = mock(FirebaseAccess.class); var db = mock(Firestore.class); var tx = mock(Transaction.class);
        when(firebase.firestore()).thenReturn(db);
        var snapshotCollection = mock(CollectionReference.class); var receiptCollection = mock(CollectionReference.class); var eventCollection = mock(CollectionReference.class);
        when(db.collection("fixturePriceSnapshots")).thenReturn(snapshotCollection);
        when(db.collection("fixtureSnapshotBatches")).thenReturn(receiptCollection); when(db.collection("snapshotAlertEvents")).thenReturn(eventCollection);
        var snapshotQuery = mock(Query.class); var receiptQuery = mock(Query.class); var eventQuery = mock(Query.class);
        when(snapshotCollection.select("createdAt")).thenReturn(snapshotQuery); when(receiptCollection.select("createdAt")).thenReturn(receiptQuery);
        when(eventCollection.select("status", "createdAt")).thenReturn(eventQuery);
        for (var query : List.of(snapshotQuery, receiptQuery, eventQuery)) when(query.limit(501)).thenReturn(query);
        var snapshots = mock(QuerySnapshot.class); var receipts = mock(QuerySnapshot.class); var events = mock(QuerySnapshot.class);
        when(tx.get(snapshotQuery)).thenReturn(ApiFutures.immediateFuture(snapshots)); when(tx.get(receiptQuery)).thenReturn(ApiFutures.immediateFuture(receipts));
        when(tx.get(eventQuery)).thenReturn(ApiFutures.immediateFuture(events));
        var doc = mock(QueryDocumentSnapshot.class); when(doc.getString("status")).thenReturn("RETRYING");
        when(snapshots.getDocuments()).thenReturn(Collections.nCopies(501, doc)); when(receipts.getDocuments()).thenReturn(List.of());
        when(events.getDocuments()).thenReturn(Collections.nCopies(501, doc));
        when(db.<Map<String, Object>>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<Map<String, Object>> callback = invocation.getArgument(0);
            return ApiFutures.immediateFuture(callback.updateCallback(tx));
        });
        var result = new OperationsStore(firebase).status();
        assertEquals(Map.of("sampled", 500, "truncated", true), result.get("snapshots"));
        var eventStatus = (Map<?, ?>) result.get("events");
        assertEquals(Map.of("RETRYING", 500), eventStatus.get("statuses")); assertEquals(true, eventStatus.get("truncated"));
        verify(db, never()).collection("users"); verify(doc, never()).get("content");
    }
}
