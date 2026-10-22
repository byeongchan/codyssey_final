package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanawaControllerTest {
    @Test void captureStoresOriginalSharedQuoteWithoutCallingCollectorAgain() throws Exception {
        var cache = mock(LiveDataCache.class);
        var shared = new DanawaController(sessions, client, observations, "operator", cache);
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "a@example.test"));
        var quote = mapper.readTree("{\"fetchedAt\":\"2026-10-22T03:00:00Z\"}");
        when(cache.fetch(eq("offers"), anyMap(), any(), any())).thenReturn(new LiveDataCache.View("key", "offers", quote, "SUCCEEDED", Long.MAX_VALUE, null));
        when(observations.save(quote)).thenReturn(Map.of("duplicate", true));
        shared.capture("token", mapper.readTree("{\"productId\":\"123\"}"));
        verify(observations).save(quote); verifyNoInteractions(client);
    }
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final DanawaClient client = mock(DanawaClient.class);
    final ObjectMapper mapper = new ObjectMapper();
    final DanawaObservations observations = mock(DanawaObservations.class);
    final DanawaController controller = new DanawaController(sessions, client, observations, "operator");
    @Test void missingSessionAndUnlistedAccountsDoNotSpendCredits() throws Exception {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.search("", mapper.readTree("{}"))).getStatusCode().value());
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("other", "a@example.test"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.search("token", mapper.readTree("{}"))).getStatusCode().value());
        verifyNoInteractions(client);
    }
    @Test void malformedIdsAndPagesNeverReachCollector() throws Exception {
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "a@example.test"));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.offers("token", mapper.readTree("{\"productId\":\"../../x\"}"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.search("token", mapper.readTree("{\"query\":\"SSD\",\"page\":51}"))).getStatusCode().value());
        verifyNoInteractions(client);
    }
    @Test void liveResponseIsPrivateAndMissingObservationTimeIsUnknownNotFresh() throws Exception {
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "a@example.test"));
        when(client.read("product", Map.of("productId", "17788451"))).thenReturn(mapper.readTree("{\"fetchedAt\":null,\"receivedAt\":\"2026-10-22T03:00:00Z\"}"));
        var response = controller.product("token", mapper.readTree("{\"productId\":\"17788451\"}"));
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control")); assertEquals("Authorization", response.getHeaders().getFirst("Vary"));
        Map<?, ?> meta = (Map<?, ?>)((Map<?, ?>)response.getBody()).get("meta");
        assertEquals(false, meta.get("synthetic")); assertEquals(true, meta.get("stale")); assertNull(meta.get("updatedAt"));
    }
    @Test void historyDoesNotContactPaidCollectorAndCaptureUsesServerFetchedPrices() throws Exception {
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "a@example.test"));
        when(observations.history("17788451")).thenReturn(Map.of("exists", false));
        controller.history("token", "17788451"); verifyNoInteractions(client);
        var fetched = mapper.readTree("{\"source\":\"reef-danawa\"}");
        when(client.read("offers", Map.of("productId", "17788451", "page", 1))).thenReturn(fetched);
        when(observations.save(fetched)).thenReturn(Map.of("duplicate", false));
        controller.capture("token", mapper.readTree("{\"productId\":\"17788451\",\"price\":1}"));
        verify(observations).save(fetched);
    }
}
