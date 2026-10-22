package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobsTest {
    private static final Instant REFERENCE = Instant.parse("2026-10-22T03:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final MutableClock clock = new MutableClock();
    private final AnalysisJobs jobs = new AnalysisJobs(pending::add, clock);

    private JsonNode result() {
        ObjectNode result = mapper.createObjectNode();
        result.put("verdict", "BUY"); result.put("expiresAt", REFERENCE.plusSeconds(86400).toString());
        return result;
    }
    private String id(Map<String, Object> job) { return (String) job.get("jobId"); }
    @Test void ownersNeverShareJobIdsCacheOrLookups() {
        var alice = jobs.submit("alice", "p", "v1", REFERENCE, this::result);
        var bob = jobs.submit("bob", "p", "v1", REFERENCE, this::result);
        assertNotEquals(id(alice), id(bob));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> jobs.get("bob", id(alice), REFERENCE)).getStatusCode().value());
        pending.remove().run();
        assertEquals("BUY", jobs.cached("alice", "v1", REFERENCE).path("verdict").asText());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> jobs.cached("bob", "v1", REFERENCE)).getStatusCode().value());
        assertFalse(jobs.get("alice", id(alice), REFERENCE).containsKey("owner"));
    }
    @Test void joinsPendingRequestsAndComputesOnce() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> first = jobs.submit("alice", "p", "v1", REFERENCE, () -> { calls.incrementAndGet(); return result(); });
        Map<String, Object> second = jobs.submit("alice", "p", "v1", REFERENCE, this::result);
        assertEquals(id(first), id(second)); assertEquals(true, second.get("reused"));
        assertEquals("QUEUED", first.get("status")); assertEquals(1, pending.size());
        pending.remove().run();
        assertEquals(1, calls.get()); assertEquals("SUCCEEDED", jobs.get("alice", id(first), REFERENCE).get("status"));
        assertEquals(true, jobs.submit("alice", "p", "v1", REFERENCE, this::result).get("cacheHit"));
    }
    @Test void cachedResultIsCopiedAndInputChangeStartsAnotherJob() {
        jobs.submit("alice", "p", "v1", REFERENCE, this::result); pending.remove().run();
        ((ObjectNode) jobs.cached("alice", "v1", REFERENCE)).put("verdict", "WAIT");
        assertEquals("BUY", jobs.cached("alice", "v1", REFERENCE).path("verdict").asText());
        assertThrows(ResponseStatusException.class, () -> jobs.cached("alice", "v2", REFERENCE));
        assertEquals("QUEUED", jobs.submit("alice", "p", "v2", REFERENCE, this::result).get("status"));
    }
    @Test void failedJobCanBeRetriedWithNewId() {
        var first = jobs.submit("alice", "p", "v1", REFERENCE, () -> { throw new IllegalStateException("secret upstream detail"); });
        pending.remove().run();
        var failed = jobs.get("alice", id(first), REFERENCE);
        assertEquals("FAILED", failed.get("status"));
        assertFalse(failed.toString().contains("secret upstream detail"));
        assertNotEquals(id(first), id(jobs.submit("alice", "p", "v1", REFERENCE, this::result)));
    }
    @Test void cacheExpiresOnWallClockEvenWhenFixtureTimeIsFixed() {
        var first = jobs.submit("alice", "p", "v1", REFERENCE, this::result); pending.remove().run();
        clock.now = clock.now.plus(AnalysisJobs.CACHE_TTL);
        assertThrows(ResponseStatusException.class, () -> jobs.cached("alice", "v1", REFERENCE));
        assertEquals("EXPIRED", jobs.get("alice", id(first), REFERENCE).get("status"));
        assertNull(jobs.get("alice", id(first), REFERENCE).get("analysis"));
        assertNotEquals(id(first), id(jobs.submit("alice", "p", "v1", REFERENCE, this::result)));
    }
    @Test void evidenceExpiryPreventsReuseBeforeCacheTtl() {
        var first = jobs.submit("alice", "p", "v1", REFERENCE, this::result); pending.remove().run();
        Instant later = REFERENCE.plusSeconds(86401);
        assertThrows(ResponseStatusException.class, () -> jobs.cached("alice", "v1", later));
        assertEquals("EXPIRED", jobs.get("alice", id(first), later).get("status"));
    }
    @Test void queueRejectionReturns429AndDoesNotLeavePhantomJob() {
        AtomicInteger calls = new AtomicInteger();
        Executor rejecting = action -> { calls.incrementAndGet(); throw new RejectedExecutionException(); };
        AnalysisJobs service = new AnalysisJobs(rejecting, clock);
        for (int i = 0; i < 2; i++) {
            var error = assertThrows(ResponseStatusException.class, () -> service.submit("alice", "p", "v1", REFERENCE, this::result));
            assertEquals(429, error.getStatusCode().value());
        }
        assertEquals(2, calls.get());
    }
    @Test void removesCompletedJobsAfterRetention() {
        var first = jobs.submit("alice", "p", "v1", REFERENCE, this::result); pending.remove().run();
        clock.now = clock.now.plus(AnalysisJobs.RETENTION);
        var error = assertThrows(ResponseStatusException.class, () -> jobs.get("alice", id(first), REFERENCE));
        assertEquals(404, error.getStatusCode().value());
    }
    private static final class MutableClock extends Clock {
        Instant now = REFERENCE;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
