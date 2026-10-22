package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionTest {
    @Test void deletingMarkerBlocksWritersInsideTheirTransaction() throws Exception {
        var tx = mock(Transaction.class); var root = mock(DocumentReference.class); var doc = mock(DocumentSnapshot.class);
        when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(doc));
        when(doc.getString("deletionState")).thenReturn("DELETING");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> PersonalData.active(tx, root)).getStatusCode().value());
        when(doc.getString("deletionState")).thenReturn("DELETED");
        assertTrue(PersonalData.deleting(tx, root));
    }
    @Test void authenticationIsDeletedOnlyAfterPersonalCollectionsAreEmpty() throws Exception {
        var access = mock(FirebaseAccess.class); var db = mock(Firestore.class); var auth = mock(FirebaseAuth.class);
        var users = mock(CollectionReference.class); var root = mock(DocumentReference.class);
        var collection = mock(CollectionReference.class); var query = mock(Query.class); var snapshot = mock(QuerySnapshot.class);
        var child = mock(QueryDocumentSnapshot.class); var childRef = mock(DocumentReference.class); var batch = mock(WriteBatch.class);
        when(access.firestore()).thenReturn(db); when(access.auth()).thenReturn(auth);
        when(db.collection("users")).thenReturn(users); when(users.document("alice")).thenReturn(root);
        when(db.<String>runTransaction(any())).thenReturn(ApiFutures.immediateFuture("DELETING"));
        when(root.collection(anyString())).thenReturn(collection); when(collection.limit(100)).thenReturn(query);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(snapshot.getDocuments()).thenReturn(List.of(child)); when(child.getReference()).thenReturn(childRef);
        when(db.batch()).thenReturn(batch); when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
        var service = new AccountDeletion(access);
        assertEquals("DELETING", service.advance("alice"));
        verify(batch).delete(childRef); verifyNoInteractions(auth);
        when(snapshot.getDocuments()).thenReturn(List.of());
        when(root.set(anyMap())).thenReturn(ApiFutures.immediateFuture(null));
        assertEquals("DELETED", service.advance("alice"));
        verify(root).collection("conversations");
        verify(root).collection("danawaWishlist");
        verify(auth).deleteUser("alice");
        verify(root).set(java.util.Map.of("deletionState", "DELETED"));
    }
}
