package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanawaObservationsTest {
    final FirebaseAccess firebase = mock(FirebaseAccess.class);
    final Firestore db = mock(Firestore.class);
    final CollectionReference roots = mock(CollectionReference.class), observations = mock(CollectionReference.class), days = mock(CollectionReference.class);
    final DocumentReference root = mock(DocumentReference.class), reference = mock(DocumentReference.class), dayRef = mock(DocumentReference.class);
    final DocumentSnapshot previous = mock(DocumentSnapshot.class), product = mock(DocumentSnapshot.class), day = mock(DocumentSnapshot.class);
    final Transaction tx = mock(Transaction.class);
    final ObjectMapper mapper = new ObjectMapper();
    JsonNode result;
    DanawaObservations service;
    @BeforeEach void setup() throws Exception {
        when(firebase.firestore()).thenReturn(db); when(db.collection("liveDanawaProducts")).thenReturn(roots); when(roots.document("17788451")).thenReturn(root);
        when(root.collection("danawaObservations")).thenReturn(observations); when(root.collection("danawaDailyQuotes")).thenReturn(days);
        when(observations.document(anyString())).thenReturn(reference); when(days.document("2020-01-01")).thenReturn(dayRef);
        when(tx.get(reference)).thenReturn(ApiFutures.immediateFuture(previous)); when(tx.get(root)).thenReturn(ApiFutures.immediateFuture(product));
        when(tx.get(dayRef)).thenReturn(ApiFutures.immediateFuture(day));
        when(db.<Map<String, Object>>runTransaction(any())).thenAnswer(call -> {
            Transaction.Function<Map<String, Object>> callback = call.getArgument(0);
            try { return ApiFutures.immediateFuture(callback.updateCallback(tx)); }
            catch (Exception ex) { return ApiFutures.immediateFailedFuture(ex); }
        });
        result = mapper.readTree("""
          {"source":"reef-danawa","synthetic":false,"action":"offers","productId":"17788451","page":1,"hasMore":false,
           "receivedAt":"2020-01-01T03:00:00Z","fetchedAt":"2020-01-01T03:00:00Z","offers":[
           {"offerKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","availability":"UNKNOWN","payment":"card",
            "price":10000,"shippingCost":0,"reportedTotal":10000,"quotedTotal":10000,"totalMismatch":false}]}
          """);
        service = new DanawaObservations(firebase, mapper);
    }
    @Test void savesObservationAndDailyLedgerAfterAllReadsInSameTransaction() throws Exception {
        assertEquals(false, service.save(result).get("duplicate"));
        var order = inOrder(tx); order.verify(tx).get(reference); order.verify(tx).get(root); order.verify(tx).get(dayRef);
        order.verify(tx).create(eq(reference), anyMap()); order.verify(tx).set(eq(dayRef), anyMap()); order.verify(tx).set(eq(root), anyMap(), any(SetOptions.class));
        verify(db, never()).collection("fixturePriceSnapshots"); verify(db, never()).collection("users");
    }
    @Test void duplicateDoesNotRewriteOrAddToDailySamples() {
        var prepared = DanawaObservationPolicy.prepare(mapper, result, Instant.now());
        when(previous.exists()).thenReturn(true); when(previous.getString("contentDigest")).thenReturn(prepared.digest());
        assertEquals(true, service.save(result).get("duplicate"));
        verify(tx, never()).create(any(DocumentReference.class), anyMap()); verify(tx, never()).set(any(DocumentReference.class), anyMap());
    }
    @Test void overlappingPageReusesSameObservationTimeAndOfferSample() {
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("page", 2);
        when(day.exists()).thenReturn(true); when(day.getString("source")).thenReturn("reef-danawa"); when(day.getBoolean("synthetic")).thenReturn(false);
        var prepared = DanawaObservationPolicy.prepare(mapper, result, Instant.now());
        when(day.get("samples")).thenReturn(prepared.samples()); when(day.getLong("observationCount")).thenReturn(1L);
        service.save(result);
        verify(tx).set(eq(dayRef), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(value ->
            Integer.valueOf(1).equals(value.get("validQuoteSamples")) && Long.valueOf(2).equals(value.get("observationCount"))));
    }
    @Test void missingHistoryHasNoInventedAverageAndNeverProducesPurchaseAdvice() throws Exception {
        var query = mock(Query.class); var results = mock(QuerySnapshot.class);
        when(days.orderBy(any(FieldPath.class), eq(Query.Direction.DESCENDING))).thenReturn(query); when(query.limit(90)).thenReturn(query);
        when(tx.get(query)).thenReturn(ApiFutures.immediateFuture(results)); when(results.getDocuments()).thenReturn(List.of());
        var history = service.history("17788451");
        assertEquals(false, history.get("exists")); assertEquals(List.of(), history.get("points"));
        var analysis = (Map<?, ?>)history.get("analysis"); assertEquals("INSUFFICIENT_DATA", analysis.get("verdict"));
        assertEquals(0, analysis.get("eligibleObservedDays")); assertNull(analysis.get("averageQuotedPrice"));
    }
}
