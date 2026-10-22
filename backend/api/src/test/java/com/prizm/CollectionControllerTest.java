package com.prizm;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionControllerTest {
    @Test void acceptedJobsHaveLocationAndMalformedInputsDoNotReachWorker() throws Exception {
        var jobs = mock(CollectionJobs.class); var catalog = mock(CatalogRepository.class);
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(jobs.submit("q", "", "normal", "READ_ONLY")).thenReturn(Map.of("jobId", "abc", "status", "QUEUED"));
        var mvc = MockMvcBuilders.standaloneSetup(new CollectionController(jobs, catalog)).setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
        mvc.perform(post("/api/v1/collection-jobs").contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"q\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/collection-jobs/abc"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.status").value("QUEUED"));
        mvc.perform(post("/api/v1/collection-jobs").contentType(MediaType.APPLICATION_JSON).content("{\"q\":3}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/collection-jobs/bad")).andExpect(status().isBadRequest());
        verify(jobs, never()).get(anyString());
    }
}
