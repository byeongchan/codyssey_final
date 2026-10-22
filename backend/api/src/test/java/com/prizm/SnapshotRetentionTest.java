package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotRetentionTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final FixtureRepository fixture = mock(FixtureRepository.class);
    final SnapshotSelection selection = mock(SnapshotSelection.class);
    final ObjectMapper mapper = new ObjectMapper();
    final Transaction tx = mock(Transaction.class);
    final CollectionReference snapshots = mock(CollectionReference.class), events = mock(CollectionReference.class);
    final Query snapshotQuery = mock(Query.class), eventQuery = mock(Query.class);
    final QuerySnapshot snapshotResult = mock(QuerySnapshot.class), eventResult = mock(QuerySnapshot.class);
    final QueryDocumentSnapshot old = mock(QueryDocumentSnapshot.class), latest = mock(QueryDocumentSnapshot.class);
    final DocumentReference oldRef = mock(DocumentReference.class);
    SnapshotRetention service;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db);
        when(db.collection("fixturePriceSnapshots")).thenReturn(snapshots); when(db.collection("snapshotAlertEvents")).thenReturn(events);
        when(snapshots.limit(1001)).thenReturn(snapshotQuery); when(events.limit(501)).thenReturn(eventQuery);
        when(tx.get(snapshotQuery)).thenReturn(ApiFutures.immediateFuture(snapshotResult));
        when(tx.get(eventQuery)).thenReturn(ApiFutures.immediateFuture(eventResult));
        when(snapshotResult.getDocuments()).thenReturn(List.of(old, latest)); when(eventResult.getDocuments()).thenReturn(List.of());
        var content = Map.of("product", Map.of("id", "p", "optionKey", "black"));
        for (var doc : List.of(old, latest)) {
            when(doc.getLong("schemaVersion")).thenReturn(1L); when(doc.get("content")).thenReturn(content);
            when(doc.getString("productId")).thenReturn("p"); when(doc.getString("contentDigest")).thenReturn("digest");
            when(doc.getTimestamp("createdAt")).thenReturn(Timestamp.ofTimeSecondsAndNanos(1577836800, 0));
        }
        when(old.getId()).thenReturn("old"); when(latest.getId()).thenReturn("latest"); when(snapshots.document("old")).thenReturn(oldRef);
        when(fixture.categories()).thenReturn(List.of()); when(fixture.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(selection.select(any(), anyList(), anyString(), anyList())).thenReturn(new SnapshotSelection.Selected("latest", Instant.EPOCH, "", mapper.valueToTree(content)));
        when(db.<SnapshotRetention.Report>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<SnapshotRetention.Report> callback = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(callback.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        service = new SnapshotRetention(firebase, fixture, selection, mapper);
    }
    @Test void previewNeverWritesAndApplyRevalidatesBothCollectionsBeforeDeleting() throws Exception {
        var preview = service.run(true, ""); assertEquals(List.of("old"), preview.snapshotIds());
        verify(tx, never()).delete(any(DocumentReference.class));
        clearInvocations(tx);
        assertFalse(service.run(false, preview.planId()).dryRun());
        var order = inOrder(tx); order.verify(tx).get(snapshotQuery); order.verify(tx).get(eventQuery); order.verify(tx).delete(oldRef);
        verify(db, never()).collection("fixtureSnapshotBatches");
    }
    @Test void stalePlanAndInvalidArchiveNeverDelete() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.run(false, "bad-plan")).getStatusCode().value());
        when(selection.select(any(), anyList(), anyString(), anyList())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));
        assertThrows(ResponseStatusException.class, () -> service.run(true, ""));
        verify(tx, never()).delete(any(DocumentReference.class));
    }
    @Test void changedProtectionInvalidatesPreviousPreview() {
        var preview = service.run(true, "");
        when(selection.select(any(), anyList(), anyString(), anyList())).thenReturn(
            new SnapshotSelection.Selected("old", Instant.EPOCH, "", mapper.createObjectNode()));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> service.run(false, preview.planId())).getStatusCode().value());
        verify(tx, never()).delete(any(DocumentReference.class));
    }
    @Test void overLimitDoesNotPartiallyCleanCollection() {
        when(eventResult.getDocuments()).thenReturn(Collections.nCopies(501, old));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.run(true, "")).getStatusCode().value());
        verify(tx, never()).delete(any(DocumentReference.class));
    }
}
