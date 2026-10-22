package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Repository
@Primary
public class SnapshotCatalogRepository implements CatalogRepository {
    private record View(List<JsonNode> products, Map<String, SnapshotSelection.Selected> selected, String version, String loadedAt) {}
    public interface Scope extends AutoCloseable { void close(); }
    private final FixtureRepository fixture;
    private final SnapshotArchive archive;
    private final SnapshotSelection selection;
    private final ProductCache cache;
    private final String mode;
    private final AtomicReference<View> current = new AtomicReference<>();
    private final ThreadLocal<View> pinned = new ThreadLocal<>();
    private volatile String lastError;
    public SnapshotCatalogRepository(FixtureRepository fixture, SnapshotArchive archive, SnapshotSelection selection, ProductCache cache,
            @Value("${prizm.catalog-source}") String mode) {
        if (!List.of("fixture", "snapshot").contains(mode)) throw new IllegalArgumentException("CATALOG_SOURCE는 fixture 또는 snapshot입니다");
        this.fixture = fixture; this.archive = archive; this.selection = selection; this.cache = cache; this.mode = mode;
        String version = FixtureSnapshots.hash((fixture.dataVersion() + ":" + fixture.referenceTime() + ":" + mode).getBytes(StandardCharsets.UTF_8));
        current.set(new View(fixture.products(), Map.of(), version, Instant.now().toString()));
    }
    @PostConstruct public void initialize() {
        if ("live-no-fixtures".equals(fixture.dataVersion()) && !"fixture".equals(mode))
            throw new IllegalArgumentException("실제 연결 모드에서는 CATALOG_SOURCE=fixture를 유지하세요. 더미 파일은 읽지 않습니다.");
        if ("snapshot".equals(mode)) refresh();
    }
    public synchronized Map<String, Object> refresh() {
        if ("snapshot".equals(mode)) {
            try {
                var rows = archive.read(fixture.products().stream().map(p -> p.path("id").asText()).toList());
                var chosen = new LinkedHashMap<String, SnapshotSelection.Selected>();
                var products = new ArrayList<JsonNode>(); var versionParts = new ArrayList<String>();
                versionParts.add(fixture.dataVersion()); versionParts.add(fixture.referenceTime());
                for (var raw : fixture.products()) {
                    String id = raw.path("id").asText();
                    var selected = selection.select(raw, fixture.categories(), fixture.referenceTime(), rows.getOrDefault(id, List.of()));
                    ObjectNode product = raw.deepCopy(); product.put("catalogSource", "snapshot");
                    product.put("snapshotState", selected == null ? "MISSING" : "SELECTED");
                    if (selected == null) product.putNull("snapshotId");
                    else { product.put("snapshotId", selected.snapshotId()); chosen.put(id, selected); }
                    products.add(product); versionParts.add(id + ":" + (selected == null ? "missing" : selected.snapshotId()));
                }
                String version = FixtureSnapshots.hash(String.join("|", versionParts).getBytes(StandardCharsets.UTF_8));
                current.set(new View(List.copyOf(products), Map.copyOf(chosen), version, Instant.now().toString()));
                cache.invalidateAll(); lastError = null;
            } catch (RuntimeException ex) { lastError = "저장 근거 갱신에 실패했습니다. 마지막으로 성공한 조회 근거를 유지합니다"; throw ex; }
        }
        return status();
    }
    public void refreshAfterWrite() { if ("snapshot".equals(mode)) refresh(); }
    public String mode() { return mode; }
    public Scope scope() {
        View previous = pinned.get(); pinned.set(previous == null ? current.get() : previous);
        return () -> { if (previous == null) pinned.remove(); else pinned.set(previous); };
    }
    private View view() { return pinned.get() == null ? current.get() : pinned.get(); }
    public List<JsonNode> products() { return view().products().stream().map(p -> (JsonNode)p.deepCopy()).toList(); }
    public List<JsonNode> categories() { return fixture.categories(); }
    private List<JsonNode> rows(String id, String field) {
        if ("fixture".equals(mode)) return "offers".equals(field) ? fixture.offers(id) : fixture.prices(id);
        var selected = view().selected().get(id); if (selected == null) return List.of();
        var result = new ArrayList<JsonNode>(); selected.content().path(field).forEach(node -> result.add(node.deepCopy())); return List.copyOf(result);
    }
    public List<JsonNode> offers(String id) { return rows(id, "offers"); }
    public List<JsonNode> prices(String id) { return rows(id, "dailyPrices"); }
    public String referenceTime() { return fixture.referenceTime(); }
    public String dataVersion() { return view().version(); }
    public Map<String, Object> status() {
        var view = current.get(); var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode); data.put("dataVersion", view.version()); data.put("loadedAt", view.loadedAt());
        data.put("selectedCount", view.selected().size()); data.put("missingCount", "snapshot".equals(mode) ? view.products().size() - view.selected().size() : 0);
        data.put("lastError", lastError);
        data.put("selections", fixture.products().stream().map(p -> {
            var row = new LinkedHashMap<String, Object>(); String id = p.path("id").asText(); var selected = view.selected().get(id);
            row.put("productId", id); row.put("optionKey", p.path("optionKey").asText()); row.put("snapshotId", selected == null ? null : selected.snapshotId());
            row.put("observedAt", selected == null || selected.observedAt().equals(Instant.MIN) ? null : selected.observedAt().toString()); return row;
        }).toList());
        return data;
    }
}
