package com.prizm;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiscoveryControllerTest {
    @Test void invalidPaginationIsRejectedAndEmptyDealsAreNotErrors() throws Exception {
        var service = mock(DiscoveryService.class); var catalog = mock(CatalogRepository.class);
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z"); when(service.deals()).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new DiscoveryController(service, catalog)).setControllerAdvice(new ApiErrors())
            .addFilters(new RequestIdFilter()).build();
        mvc.perform(get("/api/v1/deals").param("limit", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/deals").param("cursor", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/deals").param("cursor", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/deals")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.meta.synthetic").value(true)).andExpect(jsonPath("$.criteria.minimumObservedDays").value(7));
    }
}
