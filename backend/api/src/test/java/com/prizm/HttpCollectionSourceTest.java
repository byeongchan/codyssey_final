package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.net.http.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpCollectionSourceTest {
    final HttpClient client = mock(HttpClient.class);
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final ObjectMapper mapper = new ObjectMapper();
    final CollectionSource.Query query = new CollectionSource.Query("sony", "", "normal");
    final String version = "a".repeat(64);
    HttpCollectionSource source;
    HttpResponse<String> response;
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() throws Exception {
        response = mock(HttpResponse.class);
        when(client.<String>send(any(HttpRequest.class), any())).thenReturn(response);
        when(catalog.dataVersion()).thenReturn(version); when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\",\"title\":\"sony\",\"categoryId\":\"audio\"}")));
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"source\":\"fixture\",\"synthetic\":true,\"mode\":\"READ_ONLY\",\"dataVersion\":\"" + version
            + "\",\"referenceTime\":\"2026-10-22T03:00:00Z\",\"productIds\":[\"p\"]}");
        source = new HttpCollectionSource(catalog, mapper, "http://127.0.0.1:4001", "test-key", client);
    }
    @Test void acceptsOnlyMatchingProductIdsAndSnapshotVersion() {
        assertEquals(List.of("p"), source.collect(query).productIds());
        String unexpected = response.body().replace("\"p\"", "\"other\"");
        when(response.body()).thenReturn(unexpected);
        assertEquals("INVALID_COLLECTOR_RESULT", assertThrows(CollectionSource.Failure.class, () -> source.collect(query)).code);
    }
    @Test void providerFailuresAreTypedAndDoNotExposeUpstreamText() {
        when(response.statusCode()).thenReturn(429); when(response.body()).thenReturn("private upstream secret");
        var failure = assertThrows(CollectionSource.Failure.class, () -> source.collect(query));
        assertEquals("PROVIDER_RATE_LIMITED", failure.code); assertTrue(failure.retryable); assertFalse(failure.getMessage().contains("secret"));
        when(response.statusCode()).thenReturn(409);
        assertFalse(assertThrows(CollectionSource.Failure.class, () -> source.collect(query)).retryable);
    }
    @Test void remoteOrCredentialBearingUrlsCannotMakeRequests() {
        for (String url : List.of("https://example.com", "http://user:password@127.0.0.1:4001", "http://127.0.0.1:4001/unexpected")) {
            var invalid = new HttpCollectionSource(catalog, mapper, url, "test", client);
            assertEquals("COLLECTOR_CONFIGURATION", assertThrows(CollectionSource.Failure.class, () -> invalid.collect(query)).code);
        }
        verifyNoInteractions(client);
    }
}
