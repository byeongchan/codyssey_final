package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanawaClientTest {
    @SuppressWarnings("unchecked")
    @Test void validatesCollectorIdentityAndSeparatesMissingSourceTime() throws Exception {
        var http = mock(HttpClient.class); HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            {"source":"reef-danawa","synthetic":false,"action":"offers","productId":"17788451",
             "localCacheHit":false,"hasMore":false,"fetchedAt":null,"receivedAt":"2026-10-22T03:00:00Z","offers":[]}
            """);
        var service = new DanawaClient(new ObjectMapper(), "http://127.0.0.1:4001", "internal-test-key", http);
        assertTrue(service.read("offers", Map.of("productId", "17788451")).path("fetchedAt").isNull());
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.read("offers", Map.of("productId", "1"))).getStatusCode().value());
    }
    @Test void neverSendsInternalKeyToRemoteCollectorAddress() {
        var http = mock(HttpClient.class);
        var service = new DanawaClient(new ObjectMapper(), "https://example.test", "private-key", http);
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.read("search", Map.of("query", "SSD"))).getStatusCode().value());
        verifyNoInteractions(http);
    }
}
