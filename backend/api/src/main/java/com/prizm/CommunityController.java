package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.firestore.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class CommunityController {
    private final FirebaseAccess firebase;
    private final SessionVerifier sessions;
    public CommunityController(FirebaseAccess firebase, SessionVerifier sessions) {
        this.firebase=firebase; this.sessions=sessions;
    }
    static String text(JsonNode body,String key,int max) {
        if(!body.isObject() || !body.path(key).isTextual()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,key+"를 입력하세요");
        String value=body.path(key).asText().strip();
        if(value.isEmpty() || value.length()>max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,key+"는 1~"+max+"자로 입력하세요");
        return value;
    }
    static void canComment(String kind,boolean deleted) {
        if(deleted) throw new ResponseStatusException(HttpStatus.CONFLICT,"삭제된 게시글입니다");
        if(!"GENERAL".equals(kind)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"공지사항에는 댓글을 작성할 수 없습니다");
    }
    static void owner(String actor,String author,boolean admin) {
        if(!actor.equals(author) && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"작성자 또는 관리자만 삭제할 수 있습니다");
    }
    static void author(String actor,String author) {
        if(!actor.equals(author)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"작성자만 수정할 수 있습니다");
    }
    private long version(DocumentSnapshot doc,String field) {
        Long value=doc.getLong(field); return value==null?doc.getLong("createdAt"):value;
    }
    private void checkVersion(DocumentSnapshot doc,JsonNode body,String field) {
        if(!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수정 전 글을 다시 불러오세요");
        if(version(doc,field)!=body.path("version").asLong()) throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 창에서 내용이 변경되었습니다. 다시 불러온 뒤 수정하세요");
    }
    private DocumentReference post(String id) {
        if(!id.matches("[A-Za-z0-9_-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"게시글 ID를 확인하세요");
        return firebase.firestore().collection("communityPosts").document(id);
    }
    private DocumentSnapshot exists(DocumentSnapshot doc) {
        if(!doc.exists() || Boolean.TRUE.equals(doc.getBoolean("deleted"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"게시글을 찾을 수 없습니다");
        return doc;
    }
    private Map<String,Object> view(DocumentSnapshot doc) {
        var data=new LinkedHashMap<String,Object>(doc.getData()); data.put("id",doc.getId()); return data;
    }
    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("data",data));
    }
    @GetMapping("/api/v1/community/posts")
    public ResponseEntity<?> list(@RequestParam(value="cursor",defaultValue="") String cursor) {
        Query query=firebase.firestore().collection("communityPosts").whereEqualTo("deleted",false).orderBy("createdAt",Query.Direction.DESCENDING).orderBy(FieldPath.documentId(),Query.Direction.DESCENDING);
        if(!cursor.isEmpty()) {
            // A previously returned cursor remains usable if that post was deleted meanwhile.
            var last=PersonalData.await(post(cursor).get());
            if(!last.exists()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"게시글 목록을 새로고침하세요");
            query=query.startAfter(last);
        }
        var rows=PersonalData.await(query.limit(21).get()).getDocuments();
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(20).map(this::view).toList());
        result.put("nextCursor",rows.size()>20?rows.get(19).getId():"");
        // Fetch independently of the chronological page so active notices are always visible.
        var notices=PersonalData.await(firebase.firestore().collection("communityPosts")
            .whereEqualTo("kind","NOTICE").whereEqualTo("deleted",false)
            .orderBy("createdAt",Query.Direction.DESCENDING).orderBy(FieldPath.documentId(),Query.Direction.DESCENDING)
            .limit(5).get()).getDocuments();
        result.put("pinnedItems",notices.stream().map(this::view).toList());
        return response(result);
    }
    @GetMapping("/api/v1/community/posts/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) { return response(view(exists(PersonalData.await(post(id).get())))); }
    @PostMapping(value="/api/v1/me/community/posts",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode body) {
        return createPost(authorization,body);
    }
    private ResponseEntity<?> createPost(String authorization,JsonNode body) {
        var user=sessions.verify(authorization);
        var parsed=CommunityBody.parse(body);
        String title=text(body,"title",120), content=parsed.content(), kind=text(body,"kind",10);
        if(!Set.of("NOTICE","GENERAL").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"게시글 종류를 확인하세요");
        String format=parsed.format();
        if(!Set.of("plain","markdown-v1","richtext-v1").contains(format)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하지 않는 본문 형식입니다");
        var db=firebase.firestore(); var ref=db.collection("communityPosts").document();
        PersonalData.await(db.runTransaction(tx->{
            var profile=tx.get(db.collection("users").document(user.uid())).get();
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            if("NOTICE".equals(kind) && !AdminAccess.members(tx.get(db.document(AdminAccess.DOCUMENT)).get()).contains(user.uid()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자만 공지사항을 작성할 수 있습니다");
            String name=profile.getString("memberDisplayName");
            if(name==null || name.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"내 정보에서 표시 이름을 먼저 등록하세요");
            Long previous=profile.getLong("communityPostedAt"); long now=System.currentTimeMillis();
            if(previous!=null && now-previous<30000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"글 작성 후 30초 뒤 다시 작성하세요");
            var data=new LinkedHashMap<String,Object>(Map.of("title",title,"content",content,"kind",kind,"authorUid",user.uid(),"authorName",name,"createdAt",now,"deleted",false));
            data.put("format",format);
            tx.set(ref,data);
            tx.set(profile.getReference(),Map.of("communityPostedAt",now),SetOptions.merge()); return true;
        }));
        return response(Map.of("id",ref.getId()));
    }
    @PatchMapping(value="/api/v1/me/community/posts/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> edit(@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode body) {
        return editPost(id,authorization,body);
    }
    private ResponseEntity<?> editPost(String id,String authorization,JsonNode body) {
        var user=sessions.verify(authorization);
        var parsed=CommunityBody.parse(body);
        String title=text(body,"title",120),content=parsed.content(),kind=text(body,"kind",10);
        String format=parsed.format();
        if(!Set.of("NOTICE","GENERAL").contains(kind) || !Set.of("plain","markdown-v1","richtext-v1").contains(format))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"게시글 종류와 본문 형식을 확인하세요");
        var ref=post(id); var db=firebase.firestore();
        var original=exists(PersonalData.await(ref.get()));
        author(user.uid(),original.getString("authorUid")); checkVersion(original,body,"updatedAt");
            PersonalData.await(db.runTransaction(tx->{
                var current=exists(tx.get(ref).get());
                var profile=tx.get(db.collection("users").document(user.uid())).get();
                author(user.uid(),current.getString("authorUid")); checkVersion(current,body,"updatedAt");
                if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
                if(("NOTICE".equals(kind) || "NOTICE".equals(current.getString("kind"))) && !AdminAccess.members(tx.get(db.document(AdminAccess.DOCUMENT)).get()).contains(user.uid()))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자만 공지사항을 수정할 수 있습니다");
                boolean changed=!title.equals(current.getString("title")) || !content.equals(current.getString("content"))
                    || !kind.equals(current.getString("kind")) || !format.equals(Objects.toString(current.getString("format"),"plain"));
                if(changed) tx.update(ref,Map.of("title",title,"content",content,"kind",kind,"format",format,
                    "updatedAt",Math.max(System.currentTimeMillis(),version(current,"updatedAt")+1)));
                return true;
            }));
        return response(Map.of("id",id));
    }
    @GetMapping("/api/v1/community/posts/{id}/comments")
    public ResponseEntity<?> comments(@PathVariable("id") String id,@RequestParam(value="cursor",defaultValue="") String cursor) {
        var parent=exists(PersonalData.await(post(id).get()));
        if(Boolean.TRUE.equals(parent.getBoolean("deleted")) || !"GENERAL".equals(parent.getString("kind"))) return response(Map.of("items",List.of(),"nextCursor",""));
        Query query=firebase.firestore().collection("communityComments").whereEqualTo("postId",id).orderBy("createdAt").orderBy(FieldPath.documentId());
        if(!cursor.isEmpty()) {
            if(!cursor.matches("[A-Za-z0-9_-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"댓글 ID를 확인하세요");
            var last=PersonalData.await(firebase.firestore().collection("communityComments").document(cursor).get());
            if(!last.exists() || !id.equals(last.getString("postId"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"댓글 페이지를 다시 조회하세요");
            query=query.startAfter(last);
        }
        var rows=PersonalData.await(query.limit(31).get()).getDocuments();
        return response(Map.of("items",rows.stream().limit(30).map(this::view).toList(),"nextCursor",rows.size()>30?rows.get(29).getId():""));
    }
    @PostMapping("/api/v1/me/community/posts/{id}/comments")
    public ResponseEntity<?> comment(@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode body) {
        var user=sessions.verify(authorization); String content=text(body,"content",1000);
        var parent=post(id); var db=firebase.firestore(); var ref=db.collection("communityComments").document();
        PersonalData.await(db.runTransaction(tx->{
            var profile=tx.get(db.collection("users").document(user.uid())).get();
            var article=exists(tx.get(parent).get());
            canComment(article.getString("kind"),Boolean.TRUE.equals(article.getBoolean("deleted")));
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            String name=profile.getString("memberDisplayName");
            if(name==null || name.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"내 정보에서 표시 이름을 먼저 등록하세요");
            long now=System.currentTimeMillis(); Long previous=profile.getLong("communityCommentedAt");
            if(previous!=null && now-previous<5000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"댓글 작성 후 5초 뒤 다시 작성하세요");
            tx.set(ref,Map.of("postId",id,"content",content,"authorUid",user.uid(),"authorName",name,"createdAt",now));
            tx.set(profile.getReference(),Map.of("communityCommentedAt",now),SetOptions.merge()); return true;
        })); return response(Map.of("id",ref.getId()));
    }
    @PatchMapping("/api/v1/me/community/comments/{id}")
    public ResponseEntity<?> editComment(@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode body) {
        var user=sessions.verify(authorization); String content=text(body,"content",1000);
        if(!id.matches("[A-Za-z0-9_-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"댓글 ID를 확인하세요");
        var db=firebase.firestore(); var ref=db.collection("communityComments").document(id);
        var result=PersonalData.await(db.runTransaction(tx->{
            var current=exists(tx.get(ref).get());
            author(user.uid(),current.getString("authorUid")); checkVersion(current,body,"editedAt");
            var parent=exists(tx.get(post(current.getString("postId"))).get());
            canComment(parent.getString("kind"),false);
            var profile=tx.get(db.collection("users").document(user.uid())).get();
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            var data=view(current);
            if(!content.equals(current.getString("content"))) {
                long editedAt=Math.max(System.currentTimeMillis(),version(current,"editedAt")+1);
                tx.update(ref,Map.of("content",content,"editedAt",editedAt));
                data.put("content",content); data.put("editedAt",editedAt);
            }
            return data;
        }));
        return response(result);
    }
    @DeleteMapping("/api/v1/me/community/posts/{id}")
    public ResponseEntity<?> deletePost(@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        return remove(authorization,post(id),true);
    }
    @DeleteMapping("/api/v1/me/community/comments/{id}")
    public ResponseEntity<?> deleteComment(@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        if(!id.matches("[A-Za-z0-9_-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"댓글 ID를 확인하세요");
        return remove(authorization,firebase.firestore().collection("communityComments").document(id),false);
    }
    private ResponseEntity<?> remove(String authorization,DocumentReference ref,boolean article) {
        var user=sessions.verify(authorization); var db=firebase.firestore();
        PersonalData.await(db.runTransaction(tx->{
            var doc=exists(tx.get(ref).get());
            var profile=tx.get(db.collection("users").document(user.uid())).get();
            boolean admin=AdminAccess.members(tx.get(db.document(AdminAccess.DOCUMENT)).get()).contains(user.uid());
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            owner(user.uid(),doc.getString("authorUid"),admin);
            if(article) tx.update(ref,Map.of("title","삭제된 게시글","content","","authorUid","","authorName","탈퇴 또는 삭제","deleted",true));
            else tx.delete(ref); return true;
        }));
        return response(Map.of("deleted",true));
    }
}
