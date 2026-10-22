package com.prizm;

import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Repository
public class FirestoreChatStore implements ChatStore {
    private final FirebaseAccess firebase;
    private final FixtureChatReply replies;
    public FirestoreChatStore(FirebaseAccess firebase, FixtureChatReply replies) { this.firebase = firebase; this.replies = replies; }
    private DocumentReference root(String uid) { return firebase.firestore().collection("users").document(uid); }
    @SuppressWarnings("unchecked")
    private List<Turn> turns(DocumentSnapshot doc) {
        var raw = (List<Map<String, Object>>) doc.get("turns");
        if (raw == null) return List.of();
        return raw.stream().map(t -> new Turn((String)t.get("requestId"), (String)t.get("question"), (String)t.get("answer"),
            (String)t.get("createdAt"), (String)t.get("referenceTime"))).toList();
    }
    public Conversation get(String uid, String id) { return new Conversation(id, turns(PersonalData.await(root(uid).collection("conversations").document(id).get()))); }
    public Conversation send(String uid, String id, String requestId, String message) {
        var db = firebase.firestore(); var root = root(uid); var document = root.collection("conversations").document(id);
        return PersonalData.await(db.runTransaction(tx -> {
            PersonalData.active(tx, root);
            var user = tx.get(root).get();
            var previous = turns(tx.get(document).get());
            var duplicate = previous.stream().filter(t -> t.requestId().equals(requestId)).findFirst();
            if (duplicate.isPresent()) {
                if (!duplicate.get().question().equals(message)) throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 요청 ID에 다른 질문을 사용할 수 없습니다");
                return new Conversation(id, previous);
            }
            if (previous.size() >= 20) throw new ResponseStatusException(HttpStatus.CONFLICT, "상품당 대화는 20회까지 보관합니다. 대화 삭제 후 다시 시작하세요");
            long now = Instant.now().toEpochMilli();
            Long start = user.getLong("chatWindowStart"), savedCount = user.getLong("chatCount");
            boolean sameWindow = start != null && now - start < 60000;
            long count = sameWindow && savedCount != null ? savedCount : 0;
            if (count >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "채팅은 사용자당 1분에 10회까지 가능합니다. 잠시 후 다시 시도하세요");
            var result = new ArrayList<>(previous);
            result.add(new Turn(requestId, message, replies.answer(id, message, previous), Instant.ofEpochMilli(now).toString(), replies.referenceTime()));
            var stored = result.stream().map(t -> Map.of("requestId", t.requestId(), "question", t.question(), "answer", t.answer(),
                "createdAt", t.createdAt(), "referenceTime", t.referenceTime())).toList();
            tx.set(document, Map.of("productId", id, "turns", stored, "updatedAt", FieldValue.serverTimestamp()));
            tx.set(root, Map.of("chatWindowStart", sameWindow ? start : now, "chatCount", count + 1), SetOptions.merge());
            return new Conversation(id, result);
        }));
    }
    public void delete(String uid, String id) { PersonalData.await(root(uid).collection("conversations").document(id).delete()); }
}
