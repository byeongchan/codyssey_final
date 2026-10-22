package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixtureSnapshotsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final SnapshotStore store = mock(SnapshotStore.class);
    final String version = "a".repeat(64), reference = "2026-10-22T03:00:00Z";
    FixtureSnapshots service;
    @BeforeEach void setup() throws Exception {
        when(catalog.dataVersion()).thenReturn(version); when(catalog.referenceTime()).thenReturn(reference);
        when(catalog.categories()).thenReturn(List.of(mapper.readTree("{\"id\":\"audio\",\"name\":\"음향\"}")));
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\",\"title\":\"상품\",\"optionKey\":\"black-new\",\"categoryId\":\"audio\"}")));
        when(catalog.offers("p")).thenReturn(List.of(mapper.readTree("""
          {"id":"o","productId":"p","store":"더미","currency":"KRW","productPrice":10000,"shippingFee":null,
           "unconditionalDiscount":0,"conditionalBenefits":[],"availability":"IN_STOCK","observedAt":"2026-10-22T02:00:00Z"}
          """)));
        when(catalog.prices("p")).thenReturn(List.of(mapper.readTree("{\"productId\":\"p\",\"date\":\"2026-10-22\",\"comparablePrice\":10000,\"validSamples\":1}")));
        service = new FixtureSnapshots(catalog, store, mapper);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void sameContentGetsStableIdentityAndKeepsObservationTimeAndUnknownShipping() {
        service.save(new CollectionSource.Result(List.of("p"), version, reference));
        service.save(new CollectionSource.Result(List.of("p"), version, reference));
        ArgumentCaptor<List<SnapshotStore.Snapshot>> rows = ArgumentCaptor.forClass((Class)List.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).commit(keys.capture(), eq(version), anyString(), rows.capture());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        var first = rows.getAllValues().get(0).get(0); var second = rows.getAllValues().get(1).get(0);
        assertEquals(first.id(), second.id());
        var offer = (Map<String, Object>) ((List<?>)first.content().get("offers")).get(0);
        assertEquals("2026-10-22T02:00:00Z", offer.get("observedAt")); assertNull(offer.get("shippingFee"));
        assertEquals(1, first.offerCount()); assertEquals(0, first.dailyPriceCount());
        assertEquals(1, first.content().get("dailyPriceAggregationVersion"));
    }
    @Test void invalidOrConflictingEvidenceNeverReachesStore() throws Exception {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.save("key", "wrong", reference, List.of("p"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.save("key", version, reference, List.of("p", "p"))).getStatusCode().value());
        when(catalog.offers("p")).thenReturn(List.of(mapper.readTree("{\"id\":\"o\",\"productId\":\"different-option\"}")));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.save("key", version, reference, List.of("p"))).getStatusCode().value());
        verifyNoInteractions(store);
    }
    @Test void emptyResultsHaveZeroSnapshotsInsteadOfInventedPriceSamples() {
        service.save("empty", version, reference, List.of());
        verify(store).commit(eq("empty"), eq(version), anyString(), eq(List.of()));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void storesRecomputedDailyMinimumAndSampleCount() {
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.offers("p").get(0).deepCopy();
        first.put("shippingFee", 0);
        var second = first.deepCopy().put("id", "other-observation").put("productPrice", 9000);
        when(catalog.offers("p")).thenReturn(List.of(first, second));
        service.save(new CollectionSource.Result(List.of("p"), version, reference));
        ArgumentCaptor<List<SnapshotStore.Snapshot>> rows = ArgumentCaptor.forClass((Class)List.class);
        verify(store).commit(startsWith("fixture-snapshot-v2:"), eq(version), anyString(), rows.capture());
        var snapshot = rows.getValue().get(0);
        JsonNode day = mapper.valueToTree(snapshot.content().get("dailyPrices")).get(0);
        assertEquals(9000, day.path("comparablePrice").asLong()); assertEquals(2, day.path("validSamples").asInt());
        assertEquals(1, snapshot.dailyPriceCount());
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void fileVersionAndObjectFieldOrderDoNotDuplicateIdenticalPriceContent() throws Exception {
        service.save(new CollectionSource.Result(List.of("p"), version, reference));
        String otherVersion = "b".repeat(64);
        when(catalog.dataVersion()).thenReturn(otherVersion);
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"categoryId\":\"audio\",\"optionKey\":\"black-new\",\"title\":\"상품\",\"id\":\"p\"}")));
        service.save(new CollectionSource.Result(List.of("p"), otherVersion, reference));
        ArgumentCaptor<List<SnapshotStore.Snapshot>> rows = ArgumentCaptor.forClass((Class)List.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).commit(keys.capture(), anyString(), anyString(), rows.capture());
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertEquals(rows.getAllValues().get(0).get(0).id(), rows.getAllValues().get(1).get(0).id());
    }
}
