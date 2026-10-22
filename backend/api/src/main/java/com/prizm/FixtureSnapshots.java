package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
public class FixtureSnapshots {
    private final CatalogRepository catalog;
    private final SnapshotStore store;
    private final ObjectMapper mapper;
    public FixtureSnapshots(@org.springframework.beans.factory.annotation.Qualifier("fixtureRepository") CatalogRepository catalog, SnapshotStore store, ObjectMapper mapper) {
        this.catalog = catalog; this.store = store;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public SnapshotStore.Receipt save(CollectionSource.Result result) {
        String key = "fixture-snapshot-v2:" + hash((result.dataVersion() + ":" + String.join(",", result.productIds().stream().sorted().toList())).getBytes(StandardCharsets.UTF_8));
        return save(key, result.dataVersion(), result.referenceTime(), result.productIds());
    }
    public SnapshotStore.Receipt save(String key, String version, String reference, List<String> ids) {
        if (key == null || !key.matches("[A-Za-z0-9:_-]{1,200}") || ids == null || ids.size() > 20
                || ids.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) || new HashSet<>(ids).size() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "스냅샷 키·상품 목록 형식을 확인하세요");
        if (!Objects.equals(catalog.dataVersion(), version) || !Objects.equals(catalog.referenceTime(), reference))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 API와 스냅샷 근거 버전·기준 시각이 다릅니다");
        var targets = ids.stream().sorted().toList(); var snapshots = new ArrayList<SnapshotStore.Snapshot>();
        int totalBytes = 0;
        for (String id : targets) {
            var product = catalog.products().stream().filter(p -> id.equals(p.path("id").asText())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 스냅샷 상품입니다"));
            var offers = catalog.offers(id).stream().sorted(Comparator.comparing(p -> p.path("id").asText())).toList();
            var prices = catalog.prices(id).stream().sorted(Comparator.comparing(p -> p.path("date").asText())).toList();
            if (offers.size() > 200 || prices.size() > 90) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품별 스냅샷 표본 한도를 초과했습니다");
            var validationCatalog = mapper.createObjectNode().put("schemaVersion", 1).put("referenceTime", reference);
            validationCatalog.set("products", mapper.valueToTree(List.of(product))); validationCatalog.set("categories", mapper.valueToTree(catalog.categories()));
            var validationPrices = mapper.createObjectNode(); validationPrices.set("offers", mapper.valueToTree(offers)); validationPrices.set("dailyPrices", mapper.valueToTree(prices));
            try {
                DataPolicy.validate(validationCatalog, validationPrices);
                prices = DailyPriceAggregation.aggregate(id, offers, prices);
                validationPrices.set("dailyPrices", mapper.valueToTree(prices));
                DataPolicy.validate(validationCatalog, validationPrices);
            }
            catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격 스냅샷의 금액·참조·관측 시각을 확인하세요"); }
            Map<String, Object> content = mapper.convertValue(Map.of("source", "fixture", "synthetic", true,
                "dailyPriceAggregationVersion", DailyPriceAggregation.VERSION,
                "product", product, "offers", offers, "dailyPrices", prices), new TypeReference<>() {});
            try {
                byte[] bytes = mapper.writeValueAsBytes(content); totalBytes += bytes.length;
                if (bytes.length > 256 * 1024 || totalBytes > 4 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격 스냅샷 크기 한도를 초과했습니다");
                snapshots.add(new SnapshotStore.Snapshot(hash(bytes), id, content, offers.size(), prices.size()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("스냅샷 직렬화 실패", ex); }
        }
        try {
            String digest = hash(mapper.writeValueAsBytes(Map.of("dataVersion", version, "snapshotIds", snapshots.stream().map(SnapshotStore.Snapshot::id).toList())));
            return store.commit(key, version, digest, List.copyOf(snapshots));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("저장 영수증 직렬화 실패", ex); }
        catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() >= 500) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "스냅샷 저장소와 에뮬레이터 설정을 확인하고 다시 시도하세요");
            throw ex;
        }
    }
}
