package com.prizm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class OperationsController {
    private final SessionVerifier sessions;
    private final CollectionJobs jobs;
    private final SnapshotCatalogRepository catalog;
    private final OperationsStore store;
    private final Set<String> operators;
    private final boolean worker, scheduler;
    public OperationsController(SessionVerifier sessions, CollectionJobs jobs, SnapshotCatalogRepository catalog, OperationsStore store,
            @Value("${prizm.operations-uids:}") String uids,
            @Value("${prizm.snapshot-alerts.enabled:true}") boolean worker,
            @Value("${prizm.alerts.scheduler-enabled:false}") boolean scheduler) {
        this.sessions = sessions; this.jobs = jobs; this.catalog = catalog; this.store = store;
        this.operators = new HashSet<>();
        for (String uid : uids.split(",")) if (!uid.isBlank()) operators.add(uid.strip());
        this.worker = worker; this.scheduler = scheduler;
    }
    @GetMapping("/operations")
    public ResponseEntity<?> status(@RequestHeader(value="Authorization", defaultValue="") String authorization) {
        var user = sessions.verify(authorization);
        if (!operators.contains(user.uid())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "로컬 운영 조회 권한이 없습니다");
        Map<String, Object> storage;
        try { storage = store.status(); }
        catch (RuntimeException ex) { storage = Map.of("state", "UNAVAILABLE", "message", "저장소 상태를 읽지 못했습니다. 에뮬레이터 설정·연결을 확인하세요."); }
        String now = Instant.now().toString();
        var data = Map.of("checkedAt", now, "collection", jobs.operations(), "catalog", catalog.status(), "storage", storage,
            "configuration", Map.of("snapshotAlertEventsEnabled", worker, "snapshotAlertEventsActive", worker && "snapshot".equals(catalog.mode()),
                "periodicAlertsEnabled", scheduler, "automaticRetention", false));
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(Map.of("data", data,
            "meta", Map.of("source", "local-operations", "synthetic", true, "referenceTime", now, "updatedAt", now, "stale", false, "requestId", RequestIds.current())));
    }
}
