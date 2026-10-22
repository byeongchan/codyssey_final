package com.prizm;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SnapshotControllerTest {
    @Test void internalAuthenticationAndInputAreRequiredBeforeSaving() throws Exception {
        var service = mock(FixtureSnapshots.class); var catalog = mock(CatalogRepository.class);
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(service.save("key", "version", "2026-10-22T03:00:00Z", List.of("p")))
            .thenReturn(new SnapshotStore.Receipt("key", false, 1, 1, 3, 7));
        var mvc = MockMvcBuilders.standaloneSetup(new SnapshotController(service, catalog, "internal-test-key", mock(SnapshotCatalogRepository.class)))
            .setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
        String body = "{\"idempotencyKey\":\"key\",\"dataVersion\":\"version\",\"referenceTime\":\"2026-10-22T03:00:00Z\",\"productIds\":[\"p\"]}";
        mvc.perform(post("/api/v1/internal/fixture-snapshots").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/internal/fixture-snapshots").header("X-Internal-Key", "internal-test-key").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post("/api/v1/internal/fixture-snapshots").header("X-Internal-Key", "internal-test-key").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.newSnapshots").value(1)).andExpect(header().string("Cache-Control", "no-store"));
    }
}

