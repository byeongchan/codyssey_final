package com.prizm;

import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionJobsTest {
    final CollectionSource source = mock(CollectionSource.class);
    final ProductCache cache = mock(ProductCache.class);
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final FixtureSnapshots snapshots = mock(FixtureSnapshots.class);
    final SnapshotCatalogRepository liveCatalog = mock(SnapshotCatalogRepository.class);
    final Queue<Runnable> pending = new ArrayDeque<>();
    final ProductCacheTest.Time clock = new ProductCacheTest.Time();
    CollectionJobs jobs;
    @BeforeEach void setup() {
        when(catalog.dataVersion()).thenReturn("v1"); when(catalog.referenceTime()).thenReturn(clock.now.toString());
        when(source.collect(any())).thenReturn(new CollectionSource.Result(List.of("p"), "v1", clock.now.toString()));
        jobs = new CollectionJobs(source, cache, catalog, snapshots, liveCatalog, pending::add, clock);
    }
    String id(Map<String, Object> job) { return (String)job.get("jobId"); }
    @Test void operationalSummaryOmitsSearchTermsAndExpiresOldRecords() {
        jobs.submit("private search", "", "normal");
        assertEquals(Map.of("QUEUED", 1), jobs.operations().get("statuses"));
        assertFalse(jobs.operations().toString().contains("private search"));
        pending.remove().run(); assertEquals(Map.of("SUCCEEDED", 1), jobs.operations().get("statuses"));
        clock.now = clock.now.plusSeconds(300); assertEquals(Map.of("EXPIRED", 1), jobs.operations().get("statuses"));
        clock.now = clock.now.plusSeconds(3300); assertEquals(0, jobs.operations().get("retained"));
    }
    @Test void mergesMatchingWorkAndInvalidatesCacheOnlyAfterSuccess() {
        var first = jobs.submit(" SONY ", "audio", "normal"); var second = jobs.submit("sony", "audio", "normal");
        assertEquals(id(first), id(second)); assertEquals(true, second.get("reused")); assertEquals(1, pending.size());
        verifyNoInteractions(source, cache);
        pending.remove().run(); assertEquals("SUCCEEDED", jobs.get(id(first)).get("status"));
        verify(cache).invalidateAll(); verify(source).collect(new CollectionSource.Query("sony", "audio", "normal"));
        assertEquals(id(first), id(jobs.submit("sony", "audio", "normal")));
        clock.now = clock.now.plusSeconds(300); assertEquals("EXPIRED", jobs.get(id(first)).get("status"));
        assertNotEquals(id(first), id(jobs.submit("sony", "audio", "normal")));
    }
    @Test void providerFailureIsJobFailureAndRetryStartsFreshWork() {
        when(source.collect(any())).thenThrow(new CollectionSource.Failure("PROVIDER_RATE_LIMITED", "잠시 후 재시도", true));
        var first = jobs.submit("q", "", "rate-limit"); pending.remove().run();
        assertEquals("FAILED", jobs.get(id(first)).get("status")); verifyNoInteractions(cache);
        assertNotEquals(id(first), id(jobs.submit("q", "", "rate-limit")));
    }
    @Test void mismatchingDataDoesNotInvalidateAndRetentionReturns404() {
        when(source.collect(any())).thenReturn(new CollectionSource.Result(List.of("p"), "other", clock.now.toString()));
        var first = jobs.submit("q", "", "normal"); pending.remove().run();
        assertEquals("FAILED", jobs.get(id(first)).get("status")); verifyNoInteractions(cache);
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> jobs.get(id(first))).getStatusCode().value());
    }
    @Test void fullQueueDoesNotLeavePhantomJobs() {
        var bounded = new CollectionJobs(source, cache, catalog, snapshots, liveCatalog, task -> { throw new RejectedExecutionException(); }, clock);
        for (int i = 0; i < 2; i++) assertEquals(429, assertThrows(ResponseStatusException.class, () -> bounded.submit("q", "", "normal")).getStatusCode().value());
    }
    @Test void snapshotModeIsSeparateWorkAndRequiresSuccessfulStorageBeforeCompletion() {
        var read = jobs.submit("q", "", "normal");
        var write = jobs.submit("q", "", "normal", "STORE_SNAPSHOT");
        assertNotEquals(id(read), id(write));
        when(snapshots.save(any(CollectionSource.Result.class))).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "에뮬레이터 연결 실패"));
        pending.remove().run(); clearInvocations(cache);
        pending.remove().run();
        assertEquals("FAILED", jobs.get(id(write)).get("status")); verifyNoInteractions(cache);
        when(snapshots.save(any(CollectionSource.Result.class))).thenReturn(new SnapshotStore.Receipt("key", true, 1, 0, 3, 7));
        var retry = jobs.submit("q", "", "normal", "STORE_SNAPSHOT"); pending.remove().run();
        assertEquals("SUCCEEDED", jobs.get(id(retry)).get("status"));
        assertNotNull(jobs.get(id(retry)).get("storage")); verify(cache).invalidateAll();
        verify(liveCatalog).refreshAfterWrite();
    }
}

