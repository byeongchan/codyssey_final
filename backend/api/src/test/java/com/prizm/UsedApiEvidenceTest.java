package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsedApiEvidenceTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void forwardsActualDetailDataWithoutRequiringAnExtractedTemplate() {
        var detail=mapper.createObjectNode().put("source","reef-karrot").put("provider","karrot").put("receivedAt","2026-10-31T00:00:00Z");
        var original=detail.putObject("product").put("productId","123").put("provider","karrot")
            .put("title","아이폰15 256GB").put("description","생활기스 있어요. 충전기는 없습니다.")
            .put("price",500000).put("status","on_sale").put("regionName","관저동")
            .put("categoryName","디지털기기").put("deliveryOptions","직거래")
            .put("sellerName","판매자").put("mannerScore",40.5).put("descriptionTruncated",false);
        original.putArray("images").add("https://example.com/photo.jpg");
        var product=UsedApiEvidence.product(mapper,detail);
        assertEquals(original.path("description"),product.path("description"));
        assertEquals(500000,product.path("price").asLong());
        assertEquals("직거래",product.path("deliveryOptions").asText());
        assertEquals(40.5,product.path("mannerScore").asDouble());
        assertEquals(1,product.path("imageCount").asInt());
        assertFalse(product.has("fields"));
        var metadata=UsedApiEvidence.metadata(mapper,detail,product);
        assertTrue(metadata.path("availableFields").toString().contains("description"));
        assertFalse(metadata.path("imageAnalysisAvailable").asBoolean());
    }
    @Test void sensitiveAndUnrecognizedFieldsAreNotForwardedAndMissingValuesStayMissing() {
        var detail=mapper.createObjectNode();
        detail.putObject("product").put("title","일반 상품").put("description","연락 010-1234-5678")
            .put("sellerKey","public-id-hash").put("accountNumber","sensitive").putNull("status");
        var product=UsedApiEvidence.product(mapper,detail);
        assertFalse(product.has("sellerKey")); assertFalse(product.has("accountNumber"));
        assertFalse(product.path("description").asText().contains("010-1234-5678"));
        assertFalse(product.has("mannerScore")); assertFalse(product.has("descriptionTruncated"));
        assertTrue(product.path("status").isNull());
        assertTrue(UsedApiEvidence.metadata(mapper,detail,product).path("missingFields").toString().contains("status"));
    }
}
