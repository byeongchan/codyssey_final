package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanawaWishlistTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference users = mock(CollectionReference.class), collection = mock(CollectionReference.class), display = mock(CollectionReference.class);
    final DocumentReference root = mock(DocumentReference.class), entry = mock(DocumentReference.class), detail = mock(DocumentReference.class);
    final DocumentSnapshot user = mock(DocumentSnapshot.class), previous = mock(DocumentSnapshot.class), product = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final LiveDataCache cache = mock(LiveDataCache.class);
    final ObjectMapper mapper = new ObjectMapper();
    DanawaWishlist wishlist;

    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("users")).thenReturn(users); when(users.document("alice")).thenReturn(root);
        when(root.collection("danawaWishlist")).thenReturn(collection); when(collection.document("123")).thenReturn(entry);
        when(db.collection("liveDisplayCache")).thenReturn(display); when(cache.key("product", Map.of("productId", "123"))).thenReturn("hash");
        when(display.document("hash")).thenReturn(detail); when(detail.get()).thenReturn(ApiFutures.immediateFuture(product));
        when(product.getString("payloadJson")).thenReturn("{\"product\":{\"productId\":\"123\",\"title\":\"서버 상품\",\"image\":null,\"lowestPrice\":12000},\"fetchedAt\":null}");
        when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(user)); when(tx.get(entry)).thenReturn(ApiFutures.immediateFuture(previous));
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(invocation -> {
            Transaction.Function<Object> function = invocation.getArgument(0);
            return ApiFutures.immediateFuture(function.updateCallback(tx));
        });
        wishlist = new DanawaWishlist(firebase, mapper, cache);
    }
    @Test void savingIsOwnerScopedUsesServerSnapshotAndIsIdempotent() {
        wishlist.save("alice", "123", "123");
        verify(tx).create(eq(entry), argThat((Map<String, Object> value) -> "서버 상품".equals(value.get("title")) && Long.valueOf(12000).equals(value.get("listedPrice"))));
        verify(tx).set(root, Map.of("danawaWishlistCount", 1L), SetOptions.merge());
        when(previous.exists()).thenReturn(true);
        wishlist.save("alice", "123", "123");
        verify(tx, times(1)).create(eq(entry), anyMap());
        verify(users, never()).document("bob");
    }
    @Test void deletingAccountAndCapacityLimitBlockNewSaves() {
        when(user.getString("deletionState")).thenReturn("DELETING");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> wishlist.save("alice", "123", "123")).getStatusCode().value());
        when(user.getString("deletionState")).thenReturn(null); when(user.getLong("danawaWishlistCount")).thenReturn(100L);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> wishlist.save("alice", "123", "123")).getStatusCode().value());
        verify(tx, never()).create(any(), anyMap());
    }
    @Test void removalIsIdempotentAndNeverCreatesNegativeCounts() {
        when(previous.exists()).thenReturn(true); when(user.getLong("danawaWishlistCount")).thenReturn(0L);
        wishlist.delete("alice", "123");
        verify(tx).delete(entry); verify(tx).set(root, Map.of("danawaWishlistCount", 0L), SetOptions.merge());
        when(previous.exists()).thenReturn(false); wishlist.delete("alice", "123");
        verify(tx, times(1)).delete(entry);
    }
    @Test void controllerRequiresAuthenticationAndRejectsBrowserMetadata() throws Exception {
        var sessions = mock(SessionVerifier.class); var store = mock(DanawaWishlist.class);
        var controller = new DanawaWishlistController(sessions, store);
        when(sessions.verify("")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.list("")).getStatusCode().value());
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("alice", "e@example.test"));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.save("token", "123", mapper.readTree("{\"title\":\"임의 제목\"}"))).getStatusCode().value());
        controller.save("token", "123", mapper.readTree("{\"catalogProductId\":\"456\"}"));
        verify(store).save("alice", "123", "456");
        verify(store, never()).save(eq("bob"), anyString(), anyString());
    }
}
