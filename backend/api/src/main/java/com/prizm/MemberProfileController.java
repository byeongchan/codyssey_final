package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.firestore.SetOptions;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
public class MemberProfileController {
    private final SessionVerifier sessions;
    private final FirebaseAccess firebase;
    private final AdminAccess admins;
    public MemberProfileController(SessionVerifier sessions,FirebaseAccess firebase,AdminAccess admins) {
        this.sessions=sessions;this.firebase=firebase;this.admins=admins;
    }
    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Vary","Authorization").body(Map.of("data",data,"meta",Map.of("requestId",RequestIds.current(),"source","firebase","synthetic",false)));
    }
    private Map<String,Object> profile(String role,String name,Long changedAt) {
        var result=new LinkedHashMap<String,Object>();
        result.put("displayName",name);result.put("role",role);
        result.put("displayNameChangedAt",changedAt==null?null:Instant.ofEpochMilli(changedAt).toString());
        result.put("nextNameChangeAt",changedAt==null?null:Instant.ofEpochMilli(changedAt+DisplayNamePolicy.INTERVAL).toString());
        return result;
    }
    @GetMapping("/api/v1/danawa/display-name-policy") public ResponseEntity<?> policy() { return response(DisplayNamePolicy.rules()); }
    @GetMapping("/api/v1/me/profile") public ResponseEntity<?> get(@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        var user=sessions.verify(authorization);
        var doc=PersonalData.await(firebase.firestore().collection("users").document(user.uid()).get());
        if(doc.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
        return response(profile(admins.role(user.uid()),doc.getString("memberDisplayName"),doc.getLong("memberNameChangedAt")));
    }
    @PatchMapping("/api/v1/me/profile") public ResponseEntity<?> update(@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode body) {
        var user=sessions.verify(authorization);
        if(!body.isObject() || body.size()!=1 || !body.path("displayName").isTextual()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"표시 이름만 수정할 수 있습니다");
        String name=DisplayNamePolicy.validate(body.path("displayName").asText());
        String role=admins.role(user.uid());
        var root=firebase.firestore().collection("users").document(user.uid());
        var saved=PersonalData.await(firebase.firestore().runTransaction(tx->{
            var doc=tx.get(root).get();
            if(doc.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            String previous=doc.getString("memberDisplayName");Long last=doc.getLong("memberNameChangedAt");
            long now=System.currentTimeMillis();
            DisplayNamePolicy.checkInterval(previous,name,last,now);
            if(!name.equals(previous)) {
                last=now;
                tx.set(root,Map.of("memberDisplayName",name,"memberNameChangedAt",now),SetOptions.merge());
            }
            return profile(role,name,last);
        }));
        return response(saved);
    }
    @PutMapping("/api/v1/admin/members/{uid}/role") public ResponseEntity<?> changeRole(@RequestHeader(value="Authorization",defaultValue="") String authorization,@PathVariable("uid") String uid,@RequestBody JsonNode body) {
        if(!body.isObject() || body.size()!=1 || !body.path("role").isTextual()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"role만 지정하세요");
        return response(admins.change(authorization,uid,body.path("role").asText()));
    }
    @GetMapping("/api/v1/admin/status") public ResponseEntity<?> admin(@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        var user=admins.requireAdmin(authorization);
        var doc=PersonalData.await(firebase.firestore().collection("users").document(user.uid()).get());
        if(doc.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
        return response(Map.of("role","ADMIN","verified",true,"communityModerationImplemented",true));
    }
}
