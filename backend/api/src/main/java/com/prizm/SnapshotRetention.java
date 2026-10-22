package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Local emulator maintenance only. All candidates and protections are read in the deleting transaction. */
@Service
public class SnapshotRetention {
    public record Report(boolean dryRun, String planId, String evaluatedAt, int snapshotsScanned, int eventsScanned,
                         List<String> snapshotIds, List<String> eventIds, int remainingCandidates) {}
    private final FirebaseAccess firebase;
    private final FixtureRepository fixture;
    private final SnapshotSelection selection;
    private final ObjectMapper mapper;
    public SnapshotRetention(FirebaseAccess firebase, FixtureRepository fixture, SnapshotSelection selection, ObjectMapper mapper) {
        this.firebase = firebase; this.fixture = fixture; this.selection = selection; this.mapper = mapper;
    }
    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
    public Report run(boolean dryRun, String confirmedPlan) {
        var db = firebase.firestore();
        // One UTC-day boundary keeps a preview stable while limiting its validity to this day.
        Instant now = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        return PersonalData.await(db.runTransaction(tx -> {
            var snapshots = tx.get(db.collection("fixturePriceSnapshots").limit(1001)).get().getDocuments();
            var events = tx.get(db.collection("snapshotAlertEvents").limit(501)).get().getDocuments();
            if (snapshots.size() > 1000 || events.size() > 500) throw conflict("정리 조회 한도(스냅샷 1000개·이벤트 500개)를 초과했습니다. 백업 후 별도 유지보수가 필요합니다");
            var groups = new LinkedHashMap<List<String>, List<SnapshotArchive.Entry>>();
            var products = new HashMap<List<String>, JsonNode>();
            long bytes = 0;
            for (var doc : snapshots) {
                if (!Long.valueOf(1).equals(doc.getLong("schemaVersion"))) throw conflict("스냅샷 문서 버전을 확인하세요");
                Object content = doc.get("content");
                int size = mapper.writeValueAsBytes(content).length; bytes += size;
                if (size > 256 * 1024 || bytes > 8 * 1024 * 1024) throw conflict("정리할 가격 근거의 크기 한도를 초과했습니다");
                JsonNode data = mapper.valueToTree(content);
                String productId = data.path("product").path("id").asText();
                String option = data.path("product").path("optionKey").asText();
                if (productId.isBlank() || option.isBlank() || !productId.equals(doc.getString("productId"))) throw conflict("상품별 보관 근거를 확인하세요");
                var group = List.of(productId, option);
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(new SnapshotArchive.Entry(doc.getId(), doc.getString("contentDigest"), data));
                products.put(group, data.path("product"));
            }
            Set<String> latest = new HashSet<>();
            for (var group : groups.entrySet()) {
                var selected = selection.select(products.get(group.getKey()), fixture.categories(), fixture.referenceTime(), group.getValue());
                if (selected == null) throw conflict("보호할 최신 근거를 결정하지 못했습니다");
                latest.add(selected.snapshotId());
            }
            var snapshotRows = new ArrayList<SnapshotRetentionPolicy.Snapshot>();
            for (var doc : snapshots) snapshotRows.add(new SnapshotRetentionPolicy.Snapshot(doc.getId(), doc.getString("productId"), instant(doc.getTimestamp("createdAt")), latest.contains(doc.getId())));
            var eventRows = new ArrayList<SnapshotRetentionPolicy.Event>();
            for (var doc : events) {
                String status = doc.getString("status"); Object raw = doc.get("productIds");
                if (!Set.of("PENDING", "PROCESSING", "RETRYING", "DONE").contains(Objects.toString(status, ""))
                        || !(raw instanceof List<?> ids) || ids.size() > 20 || ids.stream().anyMatch(id -> !(id instanceof String)))
                    throw conflict("알림 이벤트 상태·상품 목록을 확인하세요");
                Set<String> productIds = new HashSet<>(); for (Object id : ids) productIds.add((String)id);
                eventRows.add(new SnapshotRetentionPolicy.Event(doc.getId(), productIds, status, instant(doc.getTimestamp("updatedAt")),
                    doc.contains("token") || doc.contains("availableAt")));
            }
            var plan = SnapshotRetentionPolicy.plan(now, snapshotRows, eventRows);
            String planId = FixtureSnapshots.hash(mapper.writeValueAsBytes(Map.of("day", now.toString(), "snapshots", plan.snapshotIds(), "events", plan.eventIds())));
            if (!dryRun) {
                if (!planId.equals(confirmedPlan)) throw conflict("정리 계획이 달라졌습니다. dryRun으로 다시 확인하세요");
                for (String id : plan.snapshotIds()) tx.delete(db.collection("fixturePriceSnapshots").document(id));
                for (String id : plan.eventIds()) tx.delete(db.collection("snapshotAlertEvents").document(id));
            }
            return new Report(dryRun, planId, now.toString(), snapshots.size(), events.size(), plan.snapshotIds(), plan.eventIds(), plan.remainingCandidates());
        }));
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
