package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsedPurchaseJudgmentTest {
    final ObjectMapper mapper=new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode assessment() {
        var result=mapper.createObjectNode(); var report=result.putObject("detailReport"); report.put("finalJudgment","추가 확인 필요");
        var facts=report.putObject("selectedFacts"); facts.put("intent","SALE").put("priceMeaning","UNKNOWN").put("fresh6h",true);
        facts.putObject("costs").putNull("total");
        result.putObject("selectedSearchGroup").put("kind","MODEL").put("analyzedCount",10).put("centralLow",400000).put("centralHigh",600000);
        return result;
    }
    @Test void missingFieldsAndCostsDoNotEraseKnownGroupPriceEvidence() {
        var result=assessment();
        var product=mapper.createObjectNode().put("title","아이폰15").put("price",500000).put("description","생활기스 있음");
        UsedPurchaseJudgment.apply(mapper,result,product);
        var judgment=result.path("detailReport").path("purchaseJudgment");
        assertEquals("SEARCH_GROUP_REFERENCE",judgment.path("scope").asText());
        assertTrue(judgment.path("summary").asText().contains("중간 가격대"));
        assertTrue(judgment.path("reviewRequired").toString().contains("최종 총비용"));
        assertTrue(judgment.path("reviewRequired").toString().contains("판매 가능 여부"));
        assertEquals("추가 확인 필요",result.path("detailReport").path("comparisonJudgment").asText());
    }
    @Test void confirmedPriceConflictCannotBecomeAPositiveReferenceConclusion() {
        var result=assessment(); ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("detailReport").path("selectedFacts")).put("priceMeaning","CONFLICT");
        UsedPurchaseJudgment.apply(mapper,result,mapper.createObjectNode().put("title","아이폰15").put("price",500000));
        assertEquals("PRICE_REVIEW",result.path("detailReport").path("purchaseJudgment").path("scope").asText());
    }
    @Test void soldProductIsNotTreatedAsAvailableEvenWithCheapPrice() {
        var result=assessment();
        UsedPurchaseJudgment.apply(mapper,result,mapper.createObjectNode().put("title","아이폰15").put("price",100000).put("status","sold"));
        assertEquals("UNAVAILABLE",result.path("detailReport").path("purchaseJudgment").path("scope").asText());
    }
    @Test void nullIsNotAConfirmedDeliveryOption() {
        for(var nullValue:java.util.List.of(mapper.nullNode(),mapper.getNodeFactory().textNode("null"))) {
            var result=assessment(); var product=mapper.createObjectNode().put("title","아이폰16프로 1TB").put("price",1300000);
            product.set("deliveryOptions",nullValue);
            UsedPurchaseJudgment.apply(mapper,result,product);
            String known=result.path("detailReport").path("purchaseJudgment").path("confirmedEvidence").toString();
            assertFalse(known.contains("거래 방식")); assertTrue(known.contains("1,300,000원"));
        }
    }
    @Test void groupPriceJudgmentIsAdvisoryAndDistinguishesLowNormalAndHigh() {
        for(long price:new long[]{300000,500000,700000}) {
            var result=assessment();
            UsedPurchaseJudgment.apply(mapper,result,mapper.createObjectNode().put("title","아이폰15").put("price",price));
            var judgment=result.path("detailReport").path("purchaseJudgment");
            assertTrue(judgment.path("summary").asText().contains("참고로 활용"));
            assertTrue(judgment.path("summary").asText().contains(price<400000?"낮은 편":price>600000?"높은 편":"무난한 수준"));
            assertEquals(price,judgment.path("priceReference").path("listedPrice").asLong());
        }
    }
    @Test void fewCategoryPricesGiveOnlyAnObservedRangeReference() {
        var result=assessment();
        result.putObject("selectedSearchGroup").put("kind","CATEGORY").put("name","의자").put("analyzedCount",2).put("low",30000).put("high",50000);
        UsedPurchaseJudgment.apply(mapper,result,mapper.createObjectNode().put("title","의자").put("price",40000));
        var judgment=result.path("detailReport").path("purchaseJudgment");
        assertTrue(judgment.path("summary").asText().contains("같은 상품 종류"));
        assertTrue(judgment.path("summary").asText().contains("단정하기는 어렵습니다"));
        assertTrue(judgment.path("priceReference").path("smallSample").asBoolean());
    }
}
