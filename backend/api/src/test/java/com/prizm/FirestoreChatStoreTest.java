package com.prizm;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirestoreChatStoreTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference users = mock(CollectionReference.class), conversations = mock(CollectionReference.class);
    final DocumentReference root = mock(DocumentReference.class), document = mock(DocumentReference.class);
    final DocumentSnapshot user = mock(DocumentSnapshot.class), previous = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final FixtureChatReply replies = mock(FixtureChatReply.class);
    FirestoreChatStore store;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("users")).thenReturn(users);
        when(users.document("alice")).thenReturn(root); when(root.collection("conversations")).thenReturn(conversations);
        when(conversations.document("p")).thenReturn(document);
        when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(user)); when(tx.get(document)).thenReturn(ApiFutures.immediateFuture(previous));
        when(db.<ChatStore.Conversation>runTransaction(any())).thenAnswer(invocation -> {
            Transaction.Function<ChatStore.Conversation> function = invocation.getArgument(0);
            try { return ApiFutures.immediateFuture(function.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        when(replies.answer(eq("p"), anyString(), anyList())).thenReturn("fixture reply");
        when(replies.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        store = new FirestoreChatStore(firebase, replies);
    }
    Map<String, Object> turn(String id, String question) {
        return Map.of("requestId", id, "question", question, "answer", "saved", "createdAt", "now", "referenceTime", "fixture");
    }
    @Test void duplicateDoesNotConsumeQuotaOrGenerateAgain() {
        when(previous.get("turns")).thenReturn(List.of(turn("same-id", "가격")));
        assertEquals(1, store.send("alice", "p", "same-id", "가격").turns().size());
        verifyNoInteractions(replies); verify(tx, never()).set(eq(document), any());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.send("alice", "p", "same-id", "다른 질문")).getStatusCode().value());
    }
    @Test void deletionMarkerBlocksConversationAndQuotaWrites() {
        when(user.getString("deletionState")).thenReturn("DELETING");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.send("alice", "p", "id", "가격")).getStatusCode().value());
        verifyNoInteractions(replies); verify(tx, never()).set(eq(document), any());
    }
    @Test void rateAndRetentionLimitsAreEnforcedBeforeAnswering() {
        when(user.getLong("chatWindowStart")).thenReturn(System.currentTimeMillis()); when(user.getLong("chatCount")).thenReturn(10L);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> store.send("alice", "p", "id", "가격")).getStatusCode().value());
        when(previous.get("turns")).thenReturn(java.util.stream.IntStream.range(0, 20).mapToObj(i -> turn("old-" + i, "질문")).toList());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.send("alice", "p", "id", "가격")).getStatusCode().value());
        verifyNoInteractions(replies);
    }
    @Test void questionAndAnswerAreCommittedTogetherWithQuota() {
        assertEquals("fixture reply", store.send("alice", "p", "id", "가격").turns().get(0).answer());
        verify(tx).set(eq(document), argThat(value -> ((List<?>) ((Map<?, ?>) value).get("turns")).size() == 1));
        verify(tx).set(eq(root), argThat(value -> Long.valueOf(1).equals(((Map<?, ?>) value).get("chatCount"))
            && ((Number) ((Map<?, ?>) value).get("chatWindowStart")).longValue() > 0), any(SetOptions.class));
    }
}
