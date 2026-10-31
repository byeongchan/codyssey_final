package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Derived public aggregates only; never stores questions, account IDs or raw descriptions. */
final class UsedAnalysisCache {
    static Map<String,Object> searchInput(JsonNode scopes,List<JsonNode> rows) {
        var versions=new TreeMap<String,String>();
        for(var scope:scopes) versions.put(scope.path("id").asText(),scope.path("contentVersion").asText());
        var identities=rows.stream().map(row->row.path("provider").asText()+":"+row.path("productId").asText()).distinct().sorted().toList();
        return Map.of("sources",versions,"candidates",identities,"policy","used-search-agent-v2-region-v1-groups-v2");
    }
    static ObjectNode load(FirebaseAccess firebase,LiveDataCache keys,ObjectMapper mapper,String kind,Object input,
                           Instant expires,Supplier<ObjectNode> calculate) {
        String id=keys.key(kind,input);
        // Expired source evidence must never become valid through a fresh cache write.
        if(!expires.isAfter(Instant.now())) return calculate.get();
        var reference=firebase.firestore().collection("usedAnalysisCache").document(id);
        var document=PersonalData.await(reference.get(),"중고 분석 캐시 읽기");
        if(document.exists() && document.getTimestamp("retireAt")!=null
            && document.getTimestamp("retireAt").toDate().toInstant().isAfter(Instant.now())) {
            try {var data=mapper.readTree(document.getString("payloadJson")); if(data.isObject()) return (ObjectNode)data;}
            catch(Exception ignored) { /* Recompute malformed derived data from original evidence. */ }
        }
        var result=calculate.get();
        try {
            PersonalData.await(reference.set(Map.of("kind",kind,"payloadJson",mapper.writeValueAsString(result),
                "sourceKey",id,"inputJson",mapper.writeValueAsString(input),"createdAt",com.google.cloud.Timestamp.now(),
                "retireAt",com.google.cloud.Timestamp.of(Date.from(expires)))),"중고 분석 캐시 저장");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalArgumentException("분석 자료 저장 형식 오류",ex);}
        return result;
    }
    static Instant expires(JsonNode scopes) {
        Instant result=Instant.now().plusSeconds(86400);
        if(scopes.isEmpty()) return Instant.EPOCH;
        for(var scope:scopes) try {
            result=result.isBefore(Instant.parse(scope.path("fetchedAt").asText()).plusSeconds(86400))?result:Instant.parse(scope.path("fetchedAt").asText()).plusSeconds(86400);
        } catch(Exception ex) {return Instant.EPOCH;}
        return result;
    }
}
