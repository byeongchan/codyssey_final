package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me/used/wishlist")
public class UsedWishlistController {
    private final FirebaseAccess firebase; private final SessionVerifier sessions; private final ObjectMapper mapper;
    public UsedWishlistController(FirebaseAccess firebase,SessionVerifier sessions,ObjectMapper mapper) { this.firebase=firebase; this.sessions=sessions; this.mapper=mapper; }
    private ResponseEntity<?> response(Object data) { return ResponseEntity.ok().header("Cache-Control","no-store").header("Vary","Authorization").body(Map.of("data",data)); }
    @GetMapping public ResponseEntity<?> list(@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        var user=sessions.verify(authorization); var root=firebase.firestore().collection("users").document(user.uid());
        if(PersonalData.await(root.get()).getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
        return response(PersonalData.await(root.collection("usedWishlist").orderBy(FieldPath.documentId()).limit(100).get()).getDocuments().stream().map(doc->new LinkedHashMap<>(doc.getData())).toList());
    }
    @PutMapping("/{provider}/{id}") public ResponseEntity<?> save(@PathVariable("provider") String provider,@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        var user=sessions.verify(authorization); UsedController.provider(provider); UsedController.id(id);
        var db=firebase.firestore(); var root=db.collection("users").document(user.uid()); var ref=root.collection("usedWishlist").document(provider+"_"+id);
        PersonalData.await(db.runTransaction(tx->{
            var profile=tx.get(root).get(); var old=tx.get(ref).get(); var product=tx.get(db.collection("liveUsedCatalog").document(provider+"_"+id)).get();
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            if(old.exists()) return true;
            if(!product.exists() || !provider.equals(product.getString("provider"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"중고 매물을 먼저 조회하세요");
            long count=profile.getLong("usedWishlistCount")==null?0:profile.getLong("usedWishlistCount");
            if(count>=100) throw new ResponseStatusException(HttpStatus.CONFLICT,"중고 찜은 최대 100개까지 저장할 수 있습니다");
            var data=mapper.readTree(product.getString("summaryJson"));
            if(!id.equals(data.path("productId").asText()) || !data.path("title").isTextual()) throw new ResponseStatusException(HttpStatus.CONFLICT,"저장된 매물을 확인하세요");
            var item=new LinkedHashMap<String,Object>(); item.put("productId",id); item.put("provider",provider); item.put("title",data.path("title").asText()); item.put("image",data.path("image").textValue());
            item.put("listedPrice",data.path("price").isIntegralNumber()?data.path("price").longValue():null);
            item.put("priceObservedAt",product.getString("observedAt")); item.put("savedAt",Instant.now().toString());
            tx.create(ref,item); tx.set(root,Map.of("usedWishlistCount",count+1),SetOptions.merge()); return true;
        })); return response(Map.of("productId",id,"saved",true));
    }
    @DeleteMapping("/{provider}/{id}") public ResponseEntity<?> delete(@PathVariable("provider") String provider,@PathVariable("id") String id,@RequestHeader(value="Authorization",defaultValue="") String authorization) {
        var user=sessions.verify(authorization); UsedController.provider(provider); UsedController.id(id);
        var db=firebase.firestore(); var root=db.collection("users").document(user.uid()); var ref=root.collection("usedWishlist").document(provider+"_"+id);
        PersonalData.await(db.runTransaction(tx->{
            var profile=tx.get(root).get(); var old=tx.get(ref).get();
            if(profile.getString("deletionState")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"계정 삭제가 진행 중입니다");
            if(old.exists()) {
                long count=profile.getLong("usedWishlistCount")==null?0:profile.getLong("usedWishlistCount");
                tx.delete(ref); tx.set(root,Map.of("usedWishlistCount",Math.max(0,count-1)),SetOptions.merge());
            } return true;
        })); return response(Map.of("productId",id,"saved",false));
    }
}
