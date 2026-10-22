package com.prizm;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsControllerTest {
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final CollectionJobs jobs = mock(CollectionJobs.class);
    final SnapshotCatalogRepository catalog = mock(SnapshotCatalogRepository.class);
    final OperationsStore store = mock(OperationsStore.class);
    OperationsController controller(String uids) { return new OperationsController(sessions, jobs, catalog, store, uids, true, false); }
    void login(String uid) { when(sessions.verify("Bearer token")).thenReturn(new SessionVerifier.User(uid, "private@example.test")); }
    @Test void noSessionOrUnlistedUidCannotReadGlobalState() {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller("operator").status("")).getStatusCode().value());
        login("ordinary");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller("operator").status("Bearer token")).getStatusCode().value());
        login("operator");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller("").status("Bearer token")).getStatusCode().value());
        verifyNoInteractions(jobs, catalog, store);
    }
    @Test void storageFailureIsExplicitAndDoesNotExposeExceptionOrIdentity() {
        login("operator"); when(jobs.operations()).thenReturn(Map.of("retained", 0));
        when(catalog.status()).thenReturn(Map.of("mode", "fixture")); when(catalog.mode()).thenReturn("fixture");
        when(store.status()).thenThrow(new IllegalStateException("secret-internal-key"));
        var response = controller(" other, operator ").status("Bearer token");
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("Authorization", response.getHeaders().getFirst("Vary"));
        String body = response.getBody().toString();
        assertTrue(body.contains("UNAVAILABLE")); assertFalse(body.contains("secret-internal-key")); assertFalse(body.contains("private@example.test"));
        assertTrue(body.contains("snapshotAlertEventsActive=false"));
    }
    @Test void snapshotModeReportsConfiguredWorkerState() {
        login("operator"); when(jobs.operations()).thenReturn(Map.of()); when(catalog.status()).thenReturn(Map.of());
        when(catalog.mode()).thenReturn("snapshot"); when(store.status()).thenReturn(Map.of("state", "AVAILABLE"));
        assertTrue(controller("operator").status("Bearer token").getBody().toString().contains("snapshotAlertEventsActive=true"));
    }
}
