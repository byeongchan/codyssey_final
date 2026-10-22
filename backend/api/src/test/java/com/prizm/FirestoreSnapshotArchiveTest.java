package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirestoreSnapshotArchiveTest {
    @Test void readsQueriesInOneTransactionAndRejectsTruncatedCandidateSets() throws Exception {
        var firebase = mock(FirebaseAccess.class); var db = mock(Firestore.class);
        var collection = mock(CollectionReference.class); var query = mock(Query.class); var tx = mock(Transaction.class);
        var result = mock(QuerySnapshot.class); var doc = mock(QueryDocumentSnapshot.class);
        when(firebase.firestore()).thenReturn(db); when(db.collection("fixturePriceSnapshots")).thenReturn(collection);
        when(collection.whereEqualTo("productId", "p")).thenReturn(query); when(query.limit(101)).thenReturn(query);
        when(tx.get(query)).thenReturn(ApiFutures.immediateFuture(result)); when(result.getDocuments()).thenReturn(List.of(doc));
        when(doc.getId()).thenReturn("id"); when(doc.getString("contentDigest")).thenReturn("digest"); when(doc.get("content")).thenReturn(Map.of("source", "fixture"));
        when(doc.getLong("schemaVersion")).thenReturn(1L);
        when(db.<Map<String, List<SnapshotArchive.Entry>>>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<Map<String, List<SnapshotArchive.Entry>>> function = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(function.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        var archive = new FirestoreSnapshotArchive(firebase, new ObjectMapper());
        assertEquals("id", archive.read(List.of("p")).get("p").get(0).id());
        when(result.getDocuments()).thenReturn(Collections.nCopies(101, doc));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> archive.read(List.of("p"))).getStatusCode().value());
        verify(tx, never()).create(any(DocumentReference.class), any());
    }
}
