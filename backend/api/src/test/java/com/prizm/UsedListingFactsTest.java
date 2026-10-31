package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class UsedListingFactsTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void phoneModelAndCapacityAreNotRoadAddresses() {
        String title="가격제안) 아이폰16프로 1TB 블랙S급 배터리100%";
        assertEquals(title,UsedListingFacts.clean(title));
        assertEquals("-아이폰16프로 1테라 블랙 풀박스",UsedListingFacts.clean("-아이폰16프로 1테라 블랙 풀박스"));
        assertTrue(UsedListingFacts.clean("거래 장소 테헤란로 123").contains("[상세주소 제외]"));
    }
    @Test void naturalSourceAttributesRequireRealQuotesAndKeepInferencesUnconfirmed() {
        var item=mapper.createObjectNode().put("title","아이폰 15 256GB 팝니다").put("description","생활기스 있어요. 충전기 없이 본체만 판매합니다.").put("price",500000).put("status","on_sale");
        var fields=item.putObject("extractedFields");
        fields.putObject("model").put("value","아이폰 15").put("quote","아이폰 15 256GB").put("source","title").put("certainty","EXPLICIT");
        fields.putObject("condition").put("value","생활기스 있어요").put("quote","생활기스 있어요").put("source","description").put("certainty","EXPLICIT");
        fields.putObject("contents").put("value","풀박스").put("quote","풀박스").put("source","description").put("certainty","EXPLICIT");
        fields.putObject("unit").put("value","1대").put("quote","본체만 판매합니다").put("source","description").put("certainty","INFERRED");
        var facts=UsedListingFacts.read(mapper,item,Instant.now().toString());
        assertEquals("아이폰 15",facts.path("fields").path("model").path("value").asText());
        assertTrue(facts.path("fields").path("condition").path("confirmed").asBoolean());
        assertEquals("",facts.path("fields").path("contents").path("value").asText());
        assertFalse(facts.path("fields").path("unit").path("confirmed").asBoolean());
        assertFalse(facts.path("conditionsComplete").asBoolean());
        assertFalse(facts.path("validPrice").asBoolean());
    }
    com.fasterxml.jackson.databind.JsonNode read(long price,String title,String body) {
        return UsedListingFacts.read(mapper,mapper.createObjectNode().put("title",title).put("description",body).put("price",price).put("status","on_sale"),Instant.now().toString());
    }
    @Test void zeroAndFreeShippingAreNotEvidenceOfAFreeItem() {
        assertEquals("UNKNOWN",read(0,"판매합니다","무료배송").path("priceMeaning").asText());
        assertEquals("SALE",read(50000,"판매합니다","무료배송\n사은품 무료로 드려요").path("intent").asText());
        assertEquals("GIVEAWAY",read(0,"무료 나눔","상품 전체 무료 나눔").path("intent").asText());
    }
    @Test void negotiableAskingPriceDiffersFromUndeterminedOrPartialAmount() {
        assertEquals("NEGOTIABLE",read(50000,"판매합니다","판매 단위: 본체 1대\n네고 가능").path("priceMeaning").asText());
        assertEquals("UNDETERMINED",read(100,"판매합니다","가격은 현장 협의").path("priceMeaning").asText());
        assertEquals("PARTIAL_AMOUNT",read(1000,"예약금 판매합니다","표시 금액은 예약금").path("priceMeaning").asText());
        assertFalse(read(1000,"예약금 판매합니다","표시 금액은 예약금").path("costs").path("total").isNumber());
    }
    @Test void explicitlyCurrentWholeUnitPriceCanResolveTheDisplayedPlaceholder() {
        var facts=read(100,"판매합니다","표시 가격은 임시입니다\n판매 단위: 본체 1대\n현재 판매가: 5만원");
        assertEquals(50000,facts.path("askingPrice").asLong()); assertTrue(facts.path("priceResolvedFromBody").asBoolean());
        assertEquals("CONFLICT",read(50000,"판매합니다","판매가: 6만원").path("priceMeaning").asText());
    }
    @Test void missingCostsAreNeverZeroAndSensitiveTextIsRemoved() {
        assertFalse(read(50000,"판매합니다","정상 작동").path("costs").path("total").isNumber());
        String cleaned=UsedListingFacts.clean("연락 010-1234-5678\n계좌: 123-456-789\n주소: 서울시 테스트로 11\na@example.com");
        assertFalse(cleaned.contains("010-1234")); assertFalse(cleaned.contains("123-456")); assertFalse(cleaned.contains("example.com"));
    }
}
