package com.prizm;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CollectionJobs {
    private final CollectionSource source;
    private final ProductCache cache;
    private final CatalogRepository catalog;
    private final FixtureSnapshots snapshots;
    private final SnapshotCatalogRepository liveCatalog;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<CollectionSource.Query, Job> current = new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public CollectionJobs(CollectionSource source, ProductCache cache, @org.springframework.beans.factory.annotation.Qualifier("fixtureRepository") CatalogRepository catalog, FixtureSnapshots snapshots, SnapshotCatalogRepository liveCatalog) {
        this(source, cache, catalog, snapshots, liveCatalog, new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            var thread = new Thread(runnable, "prizm-collection"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy()), Clock.systemUTC());
    }
    CollectionJobs(CollectionSource source, ProductCache cache, CatalogRepository catalog, FixtureSnapshots snapshots, SnapshotCatalogRepository liveCatalog, Executor executor, Clock clock) {
        this.source = source; this.cache = cache; this.catalog = catalog; this.snapshots = snapshots; this.liveCatalog = liveCatalog; this.executor = executor; this.clock = clock;
    }
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final CollectionSource.Query query;
        final Instant createdAt;
        String status = "QUEUED";
        Instant finishedAt;
        CollectionSource.Result result;
        CollectionSource.Failure failure;
        SnapshotStore.Receipt storage;
        Job(CollectionSource.Query query, Instant now) { this.query = query; this.createdAt = now; }
    }
    private void clean() {
        var iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            var job = iterator.next();
            if (job.finishedAt != null && !clock.instant().isBefore(job.finishedAt.plus(Duration.ofHours(1)))) {
                current.remove(job.query, job); iterator.remove();
            }
        }
    }
    public synchronized Map<String, Object> submit(String q, String categoryId, String scenario) {
        return submit(q, categoryId, scenario, "READ_ONLY");
    }
    public synchronized Map<String, Object> submit(String q, String categoryId, String scenario, String mode) {
        if (q == null || q.length() > 100 || categoryId == null || !categoryId.matches("[A-Za-z0-9_-]{0,128}")
                || scenario == null || !List.of("normal", "empty", "error", "rate-limit", "unsupported").contains(scenario)
                || mode == null || !List.of("READ_ONLY", "STORE_SNAPSHOT").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "더미 재확인 요청 형식을 확인하세요");
        var query = new CollectionSource.Query(q.strip().toLowerCase(Locale.ROOT), categoryId, scenario, mode);
        if (query.q().length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정규화한 검색어는 100자 이하여야 합니다");
        clean(); var old = current.get(query);
        if (old != null && (old.finishedAt == null || "SUCCEEDED".equals(old.status) && clock.instant().isBefore(old.finishedAt.plusSeconds(300)))) return view(old, true);
        if (jobs.size() >= 200) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "수집 작업 보관 한도입니다. 잠시 후 다시 시도하세요");
        var job = new Job(query, clock.instant()); jobs.put(job.id, job); current.put(query, job);
        try { executor.execute(() -> run(job)); }
        catch (RejectedExecutionException ex) {
            jobs.remove(job.id); current.remove(query, job);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "수집 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요");
        }
        return view(job, false);
    }
    private void run(Job job) {
        synchronized (this) { job.status = "RUNNING"; }
        try {
            var result = source.collect(job.query);
            if (!Objects.equals(catalog.dataVersion(), result.dataVersion()) || !Objects.equals(catalog.referenceTime(), result.referenceTime()))
                throw new CollectionSource.Failure("FIXTURE_MISMATCH", "작업 중 더미 근거 버전이 변경되었습니다", false);
            var storage = "STORE_SNAPSHOT".equals(job.query.mode()) ? snapshots.save(result) : null;
            if (storage != null) liveCatalog.refreshAfterWrite();
            cache.invalidateAll();
            synchronized (this) { job.result = result; job.storage = storage; job.status = "SUCCEEDED"; job.finishedAt = clock.instant(); }
        } catch (Exception ex) {
            synchronized (this) {
                if (ex instanceof CollectionSource.Failure failure) job.failure = failure;
                else if (ex instanceof ResponseStatusException status) {
                    int code = status.getStatusCode().value();
                    job.failure = new CollectionSource.Failure(code == 409 ? "SNAPSHOT_CONFLICT" : code >= 500 ? "SNAPSHOT_STORE_UNAVAILABLE" : "INVALID_SNAPSHOT",
                        status.getReason() == null ? "스냅샷 저장에 실패했습니다" : status.getReason(), code >= 500);
                } else job.failure = new CollectionSource.Failure("COLLECTION_FAILED", "더미 재확인·저장에 실패했습니다", true);
                job.status = "FAILED"; job.finishedAt = clock.instant();
            }
        }
    }
    public synchronized Map<String, Object> get(String id) {
        clean(); var job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수집 작업이 없거나 보관 기간이 지났습니다");
        return view(job, false);
    }
    public synchronized Map<String, Object> operations() {
        clean(); var counts = new TreeMap<String, Integer>();
        String lastFailureCode = null, lastFinishedAt = null;
        Instant newest = null;
        for (var job : jobs.values()) {
            String status = "SUCCEEDED".equals(job.status) && !clock.instant().isBefore(job.finishedAt.plusSeconds(300)) ? "EXPIRED" : job.status;
            counts.merge(status, 1, Integer::sum);
            if (job.finishedAt != null && (newest == null || job.finishedAt.isAfter(newest))) {
                newest = job.finishedAt; lastFinishedAt = newest.toString();
                lastFailureCode = job.failure == null ? null : job.failure.code;
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("retained", jobs.size()); result.put("statuses", counts);
        result.put("lastFinishedAt", lastFinishedAt); result.put("lastFinishedErrorCode", lastFailureCode);
        result.put("scope", "PROCESS_MEMORY"); result.put("finishedRetentionSeconds", 3600);
        return result;
    }
    private Map<String, Object> view(Job job, boolean reused) {
        var view = new LinkedHashMap<String, Object>();
        boolean expired = "SUCCEEDED".equals(job.status) && !clock.instant().isBefore(job.finishedAt.plusSeconds(300));
        view.put("jobId", job.id); view.put("query", job.query); view.put("status", expired ? "EXPIRED" : job.status);
        view.put("createdAt", job.createdAt.toString()); view.put("finishedAt", job.finishedAt == null ? null : job.finishedAt.toString());
        view.put("pollAfterSeconds", 1); view.put("reused", reused); view.put("mode", job.query.mode());
        view.put("storage", expired ? null : job.storage);
        view.put("result", expired ? null : job.result);
        view.put("error", job.failure == null ? null : Map.of("code", job.failure.code, "message", job.failure.getMessage(), "retryable", job.failure.retryable));
        return view;
    }
    @PreDestroy public void close() { if (executor instanceof ExecutorService service) service.shutdownNow(); }
}
