package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Repository
public class FirestoreSnapshotArchive implements SnapshotArchive {
    private final FirebaseAccess firebase;
    private final ObjectMapper mapper;
    public FirestoreSnapshotArchive(FirebaseAccess firebase, ObjectMapper mapper) { this.firebase = firebase; this.mapper = mapper; }
    public Map<String, List<Entry>> read(List<String> ids) {
        if (ids.size() > 20) throw new ResponseStatusException(HttpStatus.CONFLICT, "스냅샷 조회는 최대 20개 카탈로그 상품을 지원합니다");
        var db = firebase.firestore();
        return PersonalData.await(db.runTransaction(tx -> {
            var result = new LinkedHashMap<String, List<Entry>>();
            int totalBytes = 0;
            for (String id : ids) {
                var query = db.collection("fixturePriceSnapshots").whereEqualTo("productId", id).limit(101);
                var docs = tx.get(query).get().getDocuments();
                if (docs.size() > 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "상품별 스냅샷 100개 한도를 초과했습니다. 보관 정책을 확인하세요");
                var entries = new ArrayList<Entry>();
                for (var doc : docs) {
                    if (!Long.valueOf(1).equals(doc.getLong("schemaVersion"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "지원하지 않는 스냅샷 문서 버전입니다");
                    var content = doc.get("content"); int bytes = mapper.writeValueAsBytes(content).length; totalBytes += bytes;
                    if (bytes > 256 * 1024) throw new ResponseStatusException(HttpStatus.CONFLICT, "상품 스냅샷 내용 256 KiB 한도를 초과했습니다");
                    if (totalBytes > 8 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.CONFLICT, "스냅샷 조회 내용 8 MiB 한도를 초과했습니다. 보관 정책을 확인하세요");
                    entries.add(new Entry(doc.getId(), doc.getString("contentDigest"), mapper.valueToTree(content)));
                }
                result.put(id, List.copyOf(entries));
            }
            return result;
        }));
    }
}
