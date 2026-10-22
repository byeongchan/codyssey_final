package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final PriceObservations observations = mock(PriceObservations.class);
    final ReviewFixtures reviews = mock(ReviewFixtures.class);
    DiscoveryService service;
    @BeforeEach void setup() throws Exception {
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\",\"title\":\"상품\",\"categoryId\":\"audio\"}")));
        when(reviews.updatedAt()).thenReturn("2026-10-22T03:00:00Z");
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(9800L, Instant.parse("2026-10-22T03:00:00Z")));
        when(catalog.prices("p")).thenReturn(history(7, 10000));
        service = new DiscoveryService(catalog, observations, reviews);
    }
    List<JsonNode> history(int days, long price) {
        return IntStream.range(0, days).mapToObj(i -> (JsonNode) mapper.createObjectNode().put("date", "2026-10-" + (22 - i)).put("comparablePrice", price)).toList();
    }
    @Test void thresholdIsExactAndDoesNotUseRoundedPercent() {
        assertEquals(1, service.deals().size()); // exactly 2%
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(9801L, Instant.parse("2026-10-22T03:00:00Z")));
        assertTrue(service.deals().isEmpty());
        when(catalog.prices("p")).thenReturn(history(7, 1_000_000_000_000L));
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(980_000_000_001L, Instant.parse("2026-10-22T03:00:00Z")));
        assertTrue(service.deals().isEmpty()); // rounds to 2.00%, but is less than 2%
    }
    @Test void staleMissingAndShortHistoryCannotProduceDeals() {
        when(catalog.prices("p")).thenReturn(history(3, 10000)); assertTrue(service.deals().isEmpty());
        when(catalog.prices("p")).thenReturn(history(7, 10000));
        when(catalog.referenceTime()).thenReturn("2026-10-24T03:00:00Z"); assertTrue(service.deals().isEmpty());
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(null, null)); assertTrue(service.deals().isEmpty());
    }
    @Test void reviewStatisticsUseActualSamplesAndSummariesAreWithheldWhenStale() throws Exception {
        when(reviews.product("p")).thenReturn(mapper.readTree("""
          {"supported":true,"reviews":[
            {"id":"a","rating":5,"text":"a","pros":["가벼움"],"cons":[]},
            {"id":"b","rating":3,"text":"b","pros":["가벼움"],"cons":[]},
            {"id":"c","rating":1,"text":"c","pros":[],"cons":["가격"]}]}
          """));
        var data = service.reviews("p");
        assertEquals("AVAILABLE", data.status()); assertEquals(3.0, data.averageRating().doubleValue(), 0.0001);
        assertEquals(1, data.positiveCount()); assertEquals(1, data.neutralCount()); assertEquals(1, data.negativeCount());
        assertEquals(2, data.pros().get(0).count());
        when(catalog.referenceTime()).thenReturn("2026-10-24T03:00:00Z");
        data = service.reviews("p"); assertEquals("STALE", data.status()); assertTrue(data.pros().isEmpty()); assertEquals(3, data.reviews().size());
    }
    @Test void unsupportedEmptyAndInsufficientAreDifferentStates() throws Exception {
        when(reviews.product("p")).thenReturn(mapper.readTree("{\"supported\":false,\"reviews\":[]}"));
        assertEquals("UNSUPPORTED", service.reviews("p").status()); assertNull(service.reviews("p").averageRating());
        when(reviews.product("p")).thenReturn(mapper.readTree("{\"supported\":true,\"reviews\":[]}"));
        assertEquals("EMPTY", service.reviews("p").status());
        when(reviews.product("p")).thenReturn(mapper.readTree("{\"supported\":true,\"reviews\":[{\"id\":\"a\",\"rating\":4,\"text\":\"a\",\"pros\":[\"x\"],\"cons\":[]}]}"));
        assertEquals("INSUFFICIENT_DATA", service.reviews("p").status()); assertTrue(service.reviews("p").pros().isEmpty());
    }
}
