package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 단일 개발 프로세스용. 작업 수와 스레드/대기열을 제한하고 같은 입력의 작업을 합친다. */
@Service
public class AnalysisJobs {
    static final Duration CACHE_TTL = Duration.ofMinutes(15);
    static final Duration RETENTION = Duration.ofHours(1);
    private static final int MAX_JOBS = 1000;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private record OwnerKey(String owner, String key) {}
    private final Map<OwnerKey, Job> current = new HashMap<>();

    public AnalysisJobs() {
        this(new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "prizm-analysis"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy()), Clock.systemUTC());
    }
    AnalysisJobs(Executor executor, Clock clock) { this.executor = executor; this.clock = clock; }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String productId;
        final String key;
        final String owner;
        final Instant createdAt;
        String status = "QUEUED";
        Instant startedAt;
        Instant finishedAt;
        JsonNode analysis;
        Job(String owner, String productId, String key, Instant now) { this.owner = owner; this.productId = productId; this.key = key; this.createdAt = now; }
    }
    private boolean reusable(Job job, Instant referenceTime) {
        if (job == null || "FAILED".equals(job.status)) return false;
        if (!"SUCCEEDED".equals(job.status)) return true;
        return clock.instant().isBefore(job.finishedAt.plus(CACHE_TTL))
                && !Instant.parse(job.analysis.path("expiresAt").asText()).isBefore(referenceTime);
    }
    private void clean() {
        Instant now = clock.instant();
        Iterator<Job> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job.finishedAt != null && !now.isBefore(job.finishedAt.plus(RETENTION))) {
                current.remove(new OwnerKey(job.owner, job.key), job); iterator.remove();
            }
        }
    }
    public synchronized Map<String, Object> submit(String owner, String productId, String key, Instant referenceTime, Supplier<JsonNode> action) {
        clean();
        var ownerKey = new OwnerKey(owner, key);
        Job existing = current.get(ownerKey);
        if (reusable(existing, referenceTime)) return view(existing, "SUCCEEDED".equals(existing.status), true);
        if (jobs.size() >= MAX_JOBS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "분석 작업 보관 한도입니다. 잠시 후 다시 시도하세요");
        Job job = new Job(owner, productId, key, clock.instant());
        jobs.put(job.id, job); current.put(ownerKey, job);
        try { executor.execute(() -> run(job, action)); }
        catch (RejectedExecutionException ex) {
            jobs.remove(job.id); current.remove(ownerKey, job);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "분석 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요");
        }
        return view(job, false, false);
    }
    private void run(Job job, Supplier<JsonNode> action) {
        synchronized (this) { job.status = "RUNNING"; job.startedAt = clock.instant(); }
        try {
            JsonNode result = action.get();
            if (result == null || !result.isObject() || !result.path("expiresAt").isTextual())
                throw new IllegalStateException("Invalid analysis result");
            Instant.parse(result.path("expiresAt").asText());
            synchronized (this) { job.analysis = result.deepCopy(); job.status = "SUCCEEDED"; job.finishedAt = clock.instant(); }
        } catch (Exception ex) {
            synchronized (this) { job.status = "FAILED"; job.finishedAt = clock.instant(); }
        }
    }
    public synchronized Map<String, Object> get(String owner, String id, Instant referenceTime) {
        clean(); Job job = jobs.get(id);
        if (job == null || !job.owner.equals(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 작업이 없거나 보관 기간이 지났습니다");
        if ("SUCCEEDED".equals(job.status) && !reusable(job, referenceTime)) return expired(view(job, false, false));
        return view(job, false, false);
    }
    static Map<String, Object> expired(Map<String, Object> view) {
        view.put("status", "EXPIRED"); view.put("analysis", null);
        view.put("error", Map.of("code", "ANALYSIS_EXPIRED", "message", "분석 결과가 만료되었거나 근거가 변경되었습니다. 다시 분석해 주세요", "retryable", true));
        return view;
    }
    /** 조회는 생성이나 재시도를 수행하지 않는다. 입력 변경/TTL 만료 시 결과 없음으로 처리한다. */
    public synchronized JsonNode cached(String owner, String key, Instant referenceTime) {
        clean(); Job job = current.get(new OwnerKey(owner, key));
        if (!reusable(job, referenceTime) || !"SUCCEEDED".equals(job.status))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 분석 결과가 없습니다. 분석 작업을 요청하세요");
        return job.analysis.deepCopy();
    }
    private Map<String, Object> view(Job job, boolean cacheHit, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.id); result.put("productId", job.productId); result.put("dataVersion", job.key);
        result.put("status", job.status); result.put("pollAfterSeconds", 1); result.put("cacheHit", cacheHit); result.put("reused", reused);
        result.put("createdAt", job.createdAt.toString());
        result.put("startedAt", job.startedAt == null ? null : job.startedAt.toString());
        result.put("finishedAt", job.finishedAt == null ? null : job.finishedAt.toString());
        result.put("analysis", job.analysis == null ? null : job.analysis.deepCopy());
        result.put("error", "FAILED".equals(job.status) ? Map.of("code", "ANALYSIS_FAILED", "message", "분석하지 못했습니다. AI 서비스와 설정을 확인하고 다시 시도하세요", "retryable", true) : null);
        return result;
    }
    @PreDestroy public void close() { if (executor instanceof ExecutorService service) service.shutdownNow(); }
}
