package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CollectionController {
    private final CollectionJobs jobs;
    private final CatalogRepository catalog;
    public CollectionController(CollectionJobs jobs, @org.springframework.beans.factory.annotation.Qualifier("fixtureRepository") CatalogRepository catalog) { this.jobs = jobs; this.catalog = catalog; }
    private Object body(Object data) { return Map.of("data", data, "meta", Map.of("source", "fixture", "synthetic", true,
        "referenceTime", catalog.referenceTime(), "updatedAt", catalog.referenceTime(), "stale", false, "requestId", RequestIds.current())); }
    @PostMapping("/collection-jobs") public ResponseEntity<?> start(@RequestBody JsonNode request) {
        if (!request.path("q").isTextual() || request.has("categoryId") && !request.path("categoryId").isTextual()
                || request.has("scenario") && !request.path("scenario").isTextual() || request.has("mode") && !request.path("mode").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수집 요청 형식을 확인하세요");
        var job = jobs.submit(request.path("q").asText(), request.path("categoryId").asText(""), request.path("scenario").asText("normal"), request.path("mode").asText("READ_ONLY"));
        return ResponseEntity.accepted().header("Cache-Control", "no-store").header("Retry-After", "1")
            .header("Location", "/api/v1/collection-jobs/" + job.get("jobId")).body(body(job));
    }
    @GetMapping("/collection-jobs/{jobId}") public ResponseEntity<?> get(@PathVariable String jobId) {
        if (!jobId.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수집 작업 ID 형식을 확인하세요");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body(jobs.get(jobId)));
    }
}
