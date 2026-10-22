package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotRetentionControllerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final SnapshotRetention service = mock(SnapshotRetention.class);
    final SnapshotRetentionController controller = new SnapshotRetentionController(service, "local-test-key");
    @Test void missingKeyDoesNotReadOrDelete() throws Exception {
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.run("", mapper.readTree("{}"))).getStatusCode().value());
        verifyNoInteractions(service);
    }
    @Test void defaultsToPreviewAndRequiresPlanForDeletion() throws Exception {
        when(service.run(true, "")).thenReturn(new SnapshotRetention.Report(true, "a".repeat(64), "2026-10-22T00:00:00Z", 0, 0, List.of(), List.of(), 0));
        assertEquals(200, controller.run("local-test-key", mapper.readTree("{}")).getStatusCode().value());
        verify(service).run(true, "");
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.run("local-test-key", mapper.readTree("{\"dryRun\":false}"))).getStatusCode().value());
        verify(service, never()).run(eq(false), anyString());
    }
    @Test void acceptsOnlyBooleanModeAndSha256Plan() throws Exception {
        when(service.run(false, "a".repeat(64))).thenReturn(new SnapshotRetention.Report(false, "a".repeat(64), "2026-10-22T00:00:00Z", 0, 0, List.of(), List.of(), 0));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.run("local-test-key", mapper.readTree("{\"dryRun\":\"false\"}"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.run("local-test-key", mapper.readTree("{\"dryRun\":false,\"planId\":\"wrong\"}"))).getStatusCode().value());
        controller.run("local-test-key", mapper.readTree("{\"dryRun\":false,\"planId\":\"" + "a".repeat(64) + "\"}"));
        verify(service).run(false, "a".repeat(64));
    }
}
