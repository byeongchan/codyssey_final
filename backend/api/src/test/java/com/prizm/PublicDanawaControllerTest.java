package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicDanawaControllerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final DanawaClient client = mock(DanawaClient.class);
    final DanawaObservations observations = mock(DanawaObservations.class);
    final DanawaController privateController = new DanawaController(sessions, client, observations, "operator");
    final PublicDanawaController controller = new PublicDanawaController(privateController);
    @Test void guestCanBrowseWithoutFirebaseOrWriteAccess() throws Exception {
        var response = mapper.readTree("{\"fetchedAt\":null,\"receivedAt\":\"2026-10-22T03:00:00Z\"}");
        when(client.read(anyString(), anyMap())).thenReturn(response);
        assertEquals(200, controller.search(mapper.readTree("{\"query\":\"SSD\"}")).getStatusCode().value());
        assertEquals(200, controller.product(mapper.readTree("{\"productId\":\"123\"}")).getStatusCode().value());
        assertEquals(200, controller.offers(mapper.readTree("{\"productId\":\"123\"}")).getStatusCode().value());
        verify(client).read("search", Map.of("query", "SSD", "page", 1));
        verifyNoInteractions(sessions, observations);
    }
    @Test void invalidPublicInputDoesNotSpendCreditsAndSavingStillRequiresLogin() throws Exception {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.product(mapper.readTree("{\"productId\":\"../x\"}"))).getStatusCode().value());
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> privateController.capture("", mapper.readTree("{\"productId\":\"123\"}"))).getStatusCode().value());
        verifyNoInteractions(client, observations);
    }
    @Test void priceHistoryUsesSharedCacheAndNeverWritesDailyObservations() throws Exception {
        var cache = mock(LiveDataCache.class);
        var browsing = new DanawaController(sessions, client, observations, "operator", cache);
        var publicApi = new PublicDanawaController(browsing, cache);
        var view = new LiveDataCache.View("key", "price_history", null, "RUNNING", 0, null);
        when(cache.fetch(eq("price_history"), eq(Map.of("productId", "123")), eq(java.time.Duration.ofHours(6)), any())).thenReturn(view);
        when(cache.response(view)).thenReturn(org.springframework.http.ResponseEntity.accepted().build());
        assertEquals(202, publicApi.priceHistory(mapper.readTree("{\"productId\":\"123\"}")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> publicApi.priceHistory(mapper.readTree("{\"productId\":\"../x\"}"))).getStatusCode().value());
        verifyNoInteractions(client, sessions, observations);
    }
}
