package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotAlertEventsTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference collection = mock(CollectionReference.class);
    final DocumentReference ref = mock(DocumentReference.class);
    final DocumentSnapshot doc = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final SnapshotAlertEvents.Claim claim = new SnapshotAlertEvents.Claim("event", "owner", Set.of("p"), "before");
    SnapshotAlertEvents events;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("snapshotAlertEvents")).thenReturn(collection);
        when(collection.document("event")).thenReturn(ref);
        when(tx.get(ref)).thenReturn(ApiFutures.immediateFuture(doc));
        when(doc.exists()).thenReturn(true); when(doc.getString("token")).thenReturn("owner");
        when(db.<Boolean>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<Boolean> callback = invocation.getArgument(0);
            return ApiFutures.immediateFuture(callback.updateCallback(tx));
        });
        events = new SnapshotAlertEvents(firebase);
    }
    @Test void expiredOwnerCannotCheckpointAnotherWorkersLease() {
        when(doc.getString("token")).thenReturn("new-owner");
        events.checkpoint(claim, ""); verify(tx, never()).update(eq(ref), anyMap());
    }
    @Test void completedPageRemovesEventFromPendingQuery() {
        events.checkpoint(claim, "");
        verify(tx).update(eq(ref), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(changes ->
            "DONE".equals(changes.get("status")) && FieldValue.delete().equals(changes.get("availableAt"))));
    }
    @Test void retryKeepsPageCursorAndSchedulesFutureAttempt() {
        events.retry(claim);
        verify(tx).update(eq(ref), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(changes ->
            "RETRYING".equals(changes.get("status")) && "before".equals(changes.get("cursor"))
                && changes.get("availableAt") instanceof com.google.cloud.Timestamp));
    }
}
