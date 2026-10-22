package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanawaExplanationControllerTest {
    @Test void invalidReviewInputCannotConsumeQuotaOrGenerateSummary() throws Exception {
        var sessions = mock(SessionVerifier.class); var quota = mock(AnalysisQuota.class);
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("user", "e@example.test"));
        var mapper = new ObjectMapper();
        var controller = new DanawaExplanationController(sessions, mock(DanawaObservations.class), quota, mapper,
            "http://127.0.0.1:8000", "key", "gemini-test", "gemini", "operator");
        for (String body : java.util.List.of("{}", "{\"reviews\":[]}", "{\"reviews\":[\" \"]}", "{\"reviews\":[1]}")) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.reviewSummary("token", "123", mapper.readTree(body))).getStatusCode().value());
        }
        verifyNoInteractions(quota);
    }
    @Test void sharedExplanationHitDoesNotConsumeQuotaOrCallGemini() throws Exception {
        var sessions = mock(SessionVerifier.class); var observations = mock(DanawaObservations.class); var quota = mock(AnalysisQuota.class); var cache = mock(LiveDataCache.class);
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "e@example.test"));
        when(observations.history("123")).thenReturn(Map.of("exists", true, "dataVersion", "evidence-a"));
        var view = new LiveDataCache.View("key", "explanation", new ObjectMapper().createObjectNode(), "SUCCEEDED", Long.MAX_VALUE, null);
        when(cache.fetch(eq("explanation"), anyMap(), any(), any())).thenReturn(view);
        when(cache.response(view)).thenReturn(org.springframework.http.ResponseEntity.ok().build());
        var controller = new DanawaExplanationController(sessions, observations, quota, new ObjectMapper(), "http://127.0.0.1:8000", "key", "gemini-test", "gemini", "operator", cache);
        assertEquals(200, controller.explain("token", "123").getStatusCode().value()); verifyNoInteractions(quota);
    }
    @Test void nonOperatorCannotReadEvidenceOrConsumePaidQuota() {
        var sessions = mock(SessionVerifier.class); var observations = mock(DanawaObservations.class); var quota = mock(AnalysisQuota.class);
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("other", "e@example.test"));
        var controller = new DanawaExplanationController(sessions, observations, quota, new ObjectMapper(), "http://127.0.0.1:8000", "key", "gemini-test", "gemini", "operator");
        var error = assertThrows(ResponseStatusException.class, () -> controller.explain("token", "123"));
        assertEquals(403, error.getStatusCode().value()); verifyNoInteractions(observations, quota);
    }
    @Test void missingObservationCannotCallModelOrConsumeQuota() {
        var sessions = mock(SessionVerifier.class); var observations = mock(DanawaObservations.class); var quota = mock(AnalysisQuota.class);
        when(sessions.verify("token")).thenReturn(new SessionVerifier.User("operator", "e@example.test"));
        when(observations.history("123")).thenReturn(Map.of("exists", false));
        var controller = new DanawaExplanationController(sessions, observations, quota, new ObjectMapper(), "http://127.0.0.1:8000", "key", "gemini-test", "gemini", "operator");
        var error = assertThrows(ResponseStatusException.class, () -> controller.explain("token", "123"));
        assertEquals(409, error.getStatusCode().value()); verifyNoInteractions(quota);
    }
}
