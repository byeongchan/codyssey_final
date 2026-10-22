package com.prizm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LiveRequestMetrics extends OncePerRequestFilter {
    private static final Map<String, Sample> samples = new ConcurrentHashMap<>();
    private static class Sample {
        final AtomicLong requests = new AtomicLong(), errors = new AtomicLong();
        final Deque<Long> recent = new ArrayDeque<>();
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/danawa/") && !path.startsWith("/api/v1/me/danawa/")) { chain.doFilter(request, response); return; }
        String label = path.replaceAll("/[a-f0-9]{64}$", "/{jobId}").replaceAll("/[0-9]{1,30}$", "/{productId}");
        Set<String> allowed = Set.of("/api/v1/danawa/search", "/api/v1/danawa/product", "/api/v1/danawa/offers", "/api/v1/danawa/price_history", "/api/v1/danawa/catalog", "/api/v1/danawa/categories",
            "/api/v1/danawa/jobs/{jobId}", "/api/v1/me/danawa/observations", "/api/v1/me/danawa/history/{productId}", "/api/v1/me/danawa/explanation/{productId}", "/api/v1/me/danawa/review-summary/{productId}", "/api/v1/me/danawa/jobs/{jobId}");
        if (!allowed.contains(label)) { chain.doFilter(request, response); return; }
        var sample = samples.computeIfAbsent(label, ignored -> new Sample()); long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            sample.requests.incrementAndGet(); if (response.getStatus() >= 400) sample.errors.incrementAndGet();
            synchronized (sample) { sample.recent.addLast(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); if (sample.recent.size() > 256) sample.recent.removeFirst(); }
        }
    }
    public static Map<String, Object> snapshot() {
        var result = new TreeMap<String, Object>();
        samples.forEach((key, sample) -> {
            List<Long> values; synchronized (sample) { values = sample.recent.stream().sorted().toList(); }
            var row = new LinkedHashMap<String, Object>(); row.put("requests", sample.requests.get()); row.put("errors", sample.errors.get()); row.put("sampleSize", values.size());
            row.put("p95Millis", values.isEmpty() ? null : values.get((int)Math.ceil(values.size() * .95) - 1)); result.put(key, row);
        });
        return result;
    }
}
