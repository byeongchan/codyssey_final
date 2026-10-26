package com.prizm;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Firestore is the sole authority. Browser writes to this document must remain denied. */
@Service
public class AdminAccess {
    public static final String DOCUMENT = "serviceAccess/administrators";
    private final SessionVerifier sessions;
    private final FirebaseAccess firebase;
    public AdminAccess(SessionVerifier sessions, FirebaseAccess firebase) { this.sessions=sessions; this.firebase=firebase; }
    static Set<String> members(DocumentSnapshot doc) {
        Object value=doc.get("uids");
        if(value==null && !doc.exists()) return Set.of();
        if(!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String s) || s.isBlank()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Firebase 관리자 uids 배열 설정을 확인하세요");
        var result=new HashSet<String>();
        for(Object item:list) result.add((String)item);
        return result;
    }
    static Set<String> changed(Set<String> previous,String target,String role) {
        var next=new HashSet<>(previous);
        if("ADMIN".equals(role)) next.add(target); else next.remove(target);
        if(next.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"마지막 관리자는 해제할 수 없습니다. 먼저 다른 관리자를 지정하세요");
        if(next.size()>100) throw new ResponseStatusException(HttpStatus.CONFLICT,"관리자는 최대 100명까지 지정할 수 있습니다");
        return next;
    }
    public String role(String uid) {
        return members(PersonalData.await(firebase.firestore().document(DOCUMENT).get(),"관리자 권한 확인")).contains(uid)?"ADMIN":"MEMBER";
    }
    public SessionVerifier.User requireAdmin(String authorization) {
        var user=sessions.verify(authorization);
        if(!"ADMIN".equals(role(user.uid()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자 권한이 필요합니다");
        return user;
    }
    public Map<String,String> change(String authorization,String target,String role) {
        var actor=requireAdmin(authorization);
        if(target.isBlank() || target.length()>128 || target.contains("/") || target.equals(".") || target.equals("..") || target.matches("__.*__"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Firebase UID를 확인하세요");
        if(!Set.of("ADMIN","MEMBER").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"권한은 ADMIN 또는 MEMBER만 가능합니다");
        if("ADMIN".equals(role)) try {
            var user=firebase.auth().getUser(target);
            if(user.isDisabled() || !user.isEmailVerified()) throw new ResponseStatusException(HttpStatus.CONFLICT,"이메일 인증된 활성 계정만 관리자로 지정할 수 있습니다");
        } catch(FirebaseAuthException ex) {
            if(ex.getAuthErrorCode()!=null && "USER_NOT_FOUND".equals(ex.getAuthErrorCode().name()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"해당 Firebase 회원이 없습니다");
            throw PersonalData.unavailable();
        }
        var db=firebase.firestore();var access=db.document(DOCUMENT);
        var audit=db.collection("adminRoleChanges").document();
        PersonalData.await(db.runTransaction(tx -> {
            var previous=members(tx.get(access).get());
            var actorDoc=tx.get(db.collection("users").document(actor.uid())).get();
            var targetDoc=tx.get(db.collection("users").document(target)).get();
            if(!previous.contains(actor.uid())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자 권한이 필요합니다");
            if(actorDoc.getString("deletionState")!=null || targetDoc.getString("deletionState")!=null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"삭제 중인 계정의 권한은 변경할 수 없습니다");
            var next=changed(previous,target,role);
            if(!next.equals(previous)) {
                long now=System.currentTimeMillis();
                tx.set(access,Map.of("uids",new ArrayList<>(new TreeSet<>(next)),"updatedAt",now));
                tx.set(audit,Map.of("actorUid",actor.uid(),"targetUid",target,"role",role,"changedAt",now));
            }
            return true;
        }),"관리자 권한 변경");
        return Map.of("uid",target,"role",role);
    }
}
