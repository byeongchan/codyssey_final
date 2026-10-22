package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisAccessTest {
    final CatalogRepository repo = mock(CatalogRepository.class);
    final AnalysisJobs jobs = mock(AnalysisJobs.class);
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final AnalysisQuota quota = mock(AnalysisQuota.class);
    final ObjectMapper mapper = new ObjectMapper();
    final ApiController controller = new ApiController(repo, mapper, "key", "http://127.0.0.1:8000", jobs, new ProductCache(), sessions, quota);
    @Test void allAnalysisRoutesRejectMissingSessionBeforeReadingCatalogOrJobs() {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class, () -> controller.analysis("p", ""));
        assertThrows(ResponseStatusException.class, () -> controller.startAnalysis("p", ""));
        assertThrows(ResponseStatusException.class, () -> controller.analysisJob("job", ""));
        verifyNoInteractions(repo, jobs, quota);
    }
    @Test void quotaFailurePreventsEnqueueAndAuthenticatedOwnerIsUsed() throws Exception {
        when(sessions.verify("Bearer token")).thenReturn(new SessionVerifier.User("alice", "a@example.test"));
        when(repo.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\"}")));
        when(repo.offers("p")).thenReturn(List.of()); when(repo.prices("p")).thenReturn(List.of());
        when(repo.referenceTime()).thenReturn("2026-10-22T03:00:00Z"); when(repo.dataVersion()).thenReturn("v1");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).when(quota).consume("alice");
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> controller.startAnalysis("p", "Bearer token")).getStatusCode().value());
        verifyNoInteractions(jobs);
        doNothing().when(quota).consume("alice");
        when(jobs.submit(eq("alice"), eq("p"), anyString(), any(), any())).thenReturn(Map.of("jobId", "j"));
        assertEquals("Authorization", controller.startAnalysis("p", "Bearer token").getHeaders().getFirst("Vary"));
        verify(jobs).submit(eq("alice"), eq("p"), anyString(), any(), any());
    }
}
