package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsedAgentPolicyTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode product(String id,long price) {
        String body="모델명: 아이폰16\n옵션: 256GB\n상태: 정상 작동\n구성품: 본체, 케이블\n판매 단위: 본체 1대\n판매 유형: 개인 판매\n판매가: "+price+"원\n배송비: 0원\n수수료: 0원\n거래 방식: 직거래";
        return mapper.createObjectNode().put("provider","karrot").put("productId",id).put("title","아이폰 판매합니다")
            .put("description",body).put("price",price).put("status","on_sale").put("observedAt",Instant.now().toString()).put("sellerKey",id);
    }
    JsonNode detail(JsonNode product) {return mapper.valueToTree(Map.of("product",product,"fetchedAt",Instant.now().toString()));}
    @Test void titleAloneDoesNotEstablishEquivalentConditions() {
        var item=product("selected",60000); var candidate=product("a",50000); candidate.remove("description");
        var result=UsedAgentPolicy.assess(mapper,detail(item),List.of(candidate));
        assertEquals(0,result.path("detailReport").path("counts").path("statisticsUsed").asInt());
        assertFalse(result.has("median")); assertEquals(0,result.path("checks").size());
    }
    @Test void referenceUsesMatchedPricesAndExcludesTheSelectedListingItself() {
        var item=product("selected",60000); var candidates=new ArrayList<JsonNode>(); candidates.add(item);
        for(int i=0;i<10;i++) candidates.add(product("ref"+i,50000+i*1000));
        var result=UsedAgentPolicy.assess(mapper,detail(item),candidates);
        assertEquals(10,result.path("detailReport").path("comparison").path("count").asInt());
        assertEquals(54500,result.path("median").asDouble());
        assertTrue(result.path("detailReport").path("comparison").has("differencePercent"));
    }
    @Test void fewerThanFiveMatchesSuppressesMedianButPreservesObservedFacts() {
        var item=product("selected",60000); var result=UsedAgentPolicy.assess(mapper,detail(item),List.of(product("ref",50000)));
        assertFalse(result.has("median")); assertEquals(60000,result.path("currentPrice").asLong());
        assertFalse(result.path("detailReport").path("recommendedLowest").asBoolean());
    }
    @Test void numericOutlierIsAReviewSignalNotAnAutomaticDeletion() {
        var item=product("selected",100); var candidates=new ArrayList<JsonNode>();
        for(int i=0;i<11;i++) candidates.add(product("ref"+i,50000+i*1000));
        var result=UsedAgentPolicy.assess(mapper,detail(item),candidates);
        assertTrue(result.path("detailReport").path("outlier").path("flagged").asBoolean());
        assertEquals(11,result.path("detailReport").path("comparison").path("count").asInt());
        assertEquals("추가 확인 필요",result.path("detailReport").path("finalJudgment").asText());
    }
    @Test void unknownStatusIsNeverTreatedAsCurrentlyAvailable() {
        var item=product("selected",60000).put("status","unknown");
        var result=UsedAgentPolicy.assess(mapper,detail(item),List.of());
        assertEquals("CHECK_AVAILABILITY",result.path("decision").asText());
        assertFalse(result.path("detailReport").path("recommendedLowest").asBoolean());
    }
    @Test void missingFeesOnlyLimitTotalCostAndDoNotBlockEligibleAskingPriceComparison() {
        var item=product("selected",60000);
        item.put("description",item.path("description").asText().replace("배송비: 0원\n","").replace("수수료: 0원\n",""));
        var candidates=new ArrayList<JsonNode>();
        for(int i=0;i<10;i++) candidates.add(product("ref"+i,50000+i*1000));
        var result=UsedAgentPolicy.assess(mapper,detail(item),candidates);
        assertTrue(result.path("detailReport").path("comparison").has("differencePercent"));
        assertTrue(result.path("detailReport").path("finalJudgment").asText().startsWith("상품가 기준"));
        assertTrue(result.path("detailReport").path("selectedFacts").path("costs").path("total").isNull());
        assertEquals(1,result.path("checks").size());
        assertTrue(result.path("checks").get(0).asText().contains("배송비"));
    }
    @Test void alreadyKnownFieldsAreNotAskedAgain() {
        var item=product("selected",60000);
        item.put("description",item.path("description").asText().replace("구성품: 본체, 케이블\n",""));
        var result=UsedAgentPolicy.assess(mapper,detail(item),List.of());
        assertEquals(1,result.path("checks").size());
        assertTrue(result.path("checks").get(0).asText().contains("구성품"));
        assertFalse(result.path("checks").get(0).asText().contains("핵심 옵션"));
    }
}
