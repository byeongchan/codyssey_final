package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Saved live products belong to the verified user; saving never calls a paid provider. */
@Service
public class DanawaWishlist {
    private final FirebaseAccess firebase;
    private final ObjectMapper mapper;
    private final LiveDataCache cache;
    public DanawaWishlist(FirebaseAccess firebase, ObjectMapper mapper, LiveDataCache cache) {
        this.firebase = firebase; this.mapper = mapper; this.cache = cache;
    }
    private DocumentReference root(String uid) { return firebase.firestore().collection("users").document(uid); }
    static void validId(String id) {
        if (!id.matches("[0-9]{1,30}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID를 확인하세요");
    }
    public List<Map<String, Object>> list(String uid) {
        var root = root(uid);
        if (PersonalData.await(root.get()).getString("deletionState") != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다");
        return PersonalData.await(root.collection("danawaWishlist").orderBy(FieldPath.documentId()).limit(100).get())
            .getDocuments().stream().map(doc -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("productId", doc.getId()); item.put("title", doc.getString("title")); item.put("image", doc.getString("image"));
                item.put("listedPrice", doc.getLong("listedPrice")); item.put("savedAt", doc.getString("savedAt"));
                item.put("priceObservedAt", doc.getString("priceObservedAt")); return (Map<String, Object>)item;
            }).toList();
    }
    private Map<String, Object> snapshot(String id, String familyId) {
        // Detail cache and search catalogue were validated by the collector, never accept browser price/title.
        var detail = PersonalData.await(firebase.firestore().collection("liveDisplayCache").document(cache.key("product", Map.of("productId", id))).get());
        try {
            if (detail.getString("payloadJson") != null) {
                var data = mapper.readTree(detail.getString("payloadJson")); var product = data.path("product");
                if (id.equals(product.path("productId").asText()) && product.path("title").isTextual())
                    return fields(id, product.path("title"), product.path("image"), product.path("lowestPrice"), data.path("fetchedAt"));
            }
            var doc = PersonalData.await(firebase.firestore().collection("liveDanawaCatalog").document(familyId).get());
            if (doc.getString("summaryJson") != null) {
                var item = mapper.readTree(doc.getString("summaryJson")); JsonNode matched = null;
                if (id.equals(item.path("productId").asText())) matched = item;
                else for (var variant : item.path("variants")) if (id.equals(variant.path("productId").asText())) { matched = variant; break; }
                if (matched != null) return fields(id, item.path("title"), doc.getString("image") == null ? item.path("image") : mapper.valueToTree(doc.getString("image")),
                    matched.path("listedPrice"), mapper.valueToTree(doc.getString("observedAt")));
            }
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 상품 정보를 확인하지 못했습니다. 상품을 다시 조회하세요"); }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저장할 상품 정보가 없습니다. 상품을 다시 조회한 뒤 찜해 주세요");
    }
    private Map<String, Object> fields(String id, JsonNode title, JsonNode image, JsonNode price, JsonNode observed) {
        if (!title.isTextual() || title.asText().isBlank() || title.asText().length() > 500) throw new IllegalArgumentException();
        var item = new LinkedHashMap<String, Object>(); item.put("productId", id); item.put("title", title.asText());
        item.put("image", image.isTextual() ? image.asText() : null);
        item.put("listedPrice", price.isIntegralNumber() && price.canConvertToLong() && price.asLong() > 0 ? price.asLong() : null);
        item.put("priceObservedAt", observed.isTextual() ? observed.asText() : null);
        item.put("savedAt", java.time.Instant.now().toString()); return item;
    }
    public void save(String uid, String id, String familyId) {
        validId(id); validId(familyId);
        var root = root(uid); var reference = root.collection("danawaWishlist").document(id);
        var item = snapshot(id, familyId);
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            var user = tx.get(root).get();
            if (user.getString("deletionState") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다");
            var old = tx.get(reference).get();
            if (old.exists()) return true; // Repeated PUT preserves original savedAt and consumes no extra slot.
            long count = user.getLong("danawaWishlistCount") == null ? 0 : user.getLong("danawaWishlistCount");
            if (count >= 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "찜은 최대 100개까지 저장할 수 있습니다");
            tx.create(reference, item); tx.set(root, Map.of("danawaWishlistCount", count + 1), SetOptions.merge()); return true;
        }));
    }
    public void delete(String uid, String id) {
        validId(id); var root = root(uid); var reference = root.collection("danawaWishlist").document(id);
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            var user = tx.get(root).get();
            if (user.getString("deletionState") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다");
            var old = tx.get(reference).get();
            if (old.exists()) {
                long count = user.getLong("danawaWishlistCount") == null ? 0 : user.getLong("danawaWishlistCount");
                tx.delete(reference); tx.set(root, Map.of("danawaWishlistCount", Math.max(0, count - 1)), SetOptions.merge());
            }
            return true;
        }));
    }
}
