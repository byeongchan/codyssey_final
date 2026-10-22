package com.prizm;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogSourceControllerTest {
    @Test void statusAndRefreshAreNotCached() throws Exception {
        var repo = mock(SnapshotCatalogRepository.class);
        when(repo.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(repo.status()).thenReturn(Map.of("mode", "snapshot", "selectedCount", 1));
        when(repo.refresh()).thenReturn(Map.of("mode", "snapshot", "selectedCount", 2));
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogSourceController(repo)).setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
        mvc.perform(get("/api/v1/catalog-source")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.selectedCount").value(1));
        mvc.perform(post("/api/v1/catalog-source/refresh")).andExpect(status().isOk()).andExpect(jsonPath("$.data.selectedCount").value(2));
    }
}
