package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotCatalogRepositoryTest {
    final FixtureRepository fixture = mock(FixtureRepository.class);
    final SnapshotArchive archive = mock(SnapshotArchive.class);
    final ProductCache cache = mock(ProductCache.class);
    final SnapshotSelectionTest data = new SnapshotSelectionTest();
    @BeforeEach void setup() throws Exception {
        data.setup(); when(fixture.products()).thenReturn(List.of(data.product)); when(fixture.categories()).thenReturn(data.categories);
        when(fixture.referenceTime()).thenReturn(data.reference); when(fixture.dataVersion()).thenReturn("original-version");
        when(fixture.offers("p")).thenReturn(List.of(new ObjectMapper().readTree("{\"productPrice\":10000}")));
    }
    SnapshotCatalogRepository repository(String mode) { return new SnapshotCatalogRepository(fixture, archive, new SnapshotSelection(new ObjectMapper()), cache, mode); }
    @Test void missingArchiveNeverUsesFilePricesAndFixtureModeNeverReadsFirestore() {
        when(archive.read(List.of("p"))).thenReturn(Map.of());
        var snapshot = repository("snapshot"); snapshot.initialize();
        assertTrue(snapshot.offers("p").isEmpty()); assertTrue(snapshot.prices("p").isEmpty());
        assertEquals("MISSING", snapshot.products().get(0).path("snapshotState").asText());
        var file = repository("fixture"); file.initialize(); file.refreshAfterWrite();
        assertEquals(10000, file.offers("p").get(0).path("productPrice").asInt()); verify(archive, times(1)).read(anyList());
    }
    @Test void refreshPublishesAtomicallyAndPinnedOperationKeepsItsOriginalView() throws Exception {
        var older = data.entry("black", "2026-10-22T01:00:00Z", 9000);
        var newer = data.entry("black", "2026-10-22T02:00:00Z", 8000);
        when(archive.read(List.of("p"))).thenReturn(Map.of("p", List.of(older)));
        var repo = repository("snapshot"); repo.initialize(); String version = repo.dataVersion();
        try (var ignored = repo.scope()) {
            when(archive.read(List.of("p"))).thenReturn(Map.of("p", List.of(newer))); repo.refresh();
            assertEquals(version, repo.dataVersion()); assertEquals(9000, repo.offers("p").get(0).path("productPrice").asInt());
        }
        assertNotEquals(version, repo.dataVersion()); assertEquals(8000, repo.offers("p").get(0).path("productPrice").asInt());
        assertEquals(8000L, new PriceObservations(repo).current("p").price().longValue());
        when(archive.read(List.of("p"))).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class, repo::refresh);
        assertEquals(8000, repo.offers("p").get(0).path("productPrice").asInt()); assertNotNull(repo.status().get("lastError"));
    }
}
