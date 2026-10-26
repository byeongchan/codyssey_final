package com.prizm;

import com.google.cloud.firestore.*;
import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AccountDeletion {
    // Current personal collections are leaves. New collections must be added here before being released.
    private static final List<String> COLLECTIONS = List.of("alerts", "notifications", "wishlist", "danawaWishlist", "priceAgentContexts", "history", "conversations");
    private final FirebaseAccess firebase;
    public AccountDeletion(FirebaseAccess firebase) { this.firebase = firebase; }

    public String advance(String uid) {
        var db = firebase.firestore(); var root = db.collection("users").document(uid);
        String state = PersonalData.await(db.runTransaction(tx -> {
            var previous = tx.get(root).get();
            var current = previous.getString("deletionState");
            if (current != null) return current;
            // Read the same authority document as role changes so deletion and grants serialize.
            if(AdminAccess.members(tx.get(db.document(AdminAccess.DOCUMENT)).get()).contains(uid))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"관리자는 탈퇴 전에 다른 관리자에게 권한 해제를 요청하세요");
            tx.set(root, Map.of("deletionState", "DELETING"));
            return "DELETING";
        }));
        if ("DELETED".equals(state)) return state;
        // One bounded batch per request. Repeated requests resume after network/process failures.
        // The root transaction guard prevents in-flight writers and the alert scheduler from recreating data.
        for (String name : COLLECTIONS) {
            var documents = PersonalData.await(root.collection(name).limit(100).get()).getDocuments();
            if (!documents.isEmpty()) {
                var batch = db.batch(); documents.forEach(doc -> batch.delete(doc.getReference()));
                PersonalData.await(batch.commit());
                return "DELETING";
            }
        }
        try { firebase.auth().deleteUser(uid); }
        catch (FirebaseAuthException ex) {
            if (ex.getAuthErrorCode() == null || !"USER_NOT_FOUND".equals(ex.getAuthErrorCode().name())) throw PersonalData.unavailable();
        }
        // Keep only a non-profile tombstone. Auth deletion may succeed even if this final write fails.
        // The DELETING marker already blocks writes, so the remaining marker needs no client retry.
        try { PersonalData.await(root.set(Map.of("deletionState", "DELETED"))); }
        catch (org.springframework.web.server.ResponseStatusException ignored) { /* No personal payload remains. */ }
        return "DELETED";
    }
}
