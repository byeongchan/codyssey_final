package com.prizm;

import com.google.cloud.firestore.SetOptions;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.Map;

/** Shared emulator counter, independent of the API process and fixture clock. */
@Service
public class AnalysisQuota {
    private final FirebaseAccess firebase;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisQuota(FirebaseAccess firebase) { this(firebase, Clock.systemUTC()); }
    AnalysisQuota(FirebaseAccess firebase, Clock clock) { this.firebase = firebase; this.clock = clock; }
    public void consume(String uid) {
        var db = firebase.firestore(); var root = db.collection("users").document(uid);
        long now = clock.millis();
        PersonalData.await(db.runTransaction(tx -> {
            var previous = tx.get(root).get();
            if (previous.getString("deletionState") != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다");
            Long start = previous.getLong("analysisWindowStart"), count = previous.getLong("analysisCount");
            boolean same = start != null && now >= start && now - start < 60_000;
            long used = same && count != null ? count : 0;
            if (used >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "분석 요청은 사용자당 1분에 5회까지 가능합니다. 잠시 후 다시 시도하세요");
            tx.set(root, Map.of("analysisWindowStart", same ? start : now, "analysisCount", used + 1), SetOptions.merge());
            return true;
        }));
    }
}
