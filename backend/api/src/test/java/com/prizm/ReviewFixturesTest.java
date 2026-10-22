package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewFixturesTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void malformedOrMisleadingReviewsAreRejectedAtStartup() throws Exception {
        var catalog = mock(CatalogRepository.class);
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\"}")));
        var valid = (ObjectNode) mapper.readTree("""
          {"schemaVersion":1,"updatedAt":"2026-10-22T03:00:00Z","products":[
            {"productId":"p","supported":true,"reviews":[{"id":"r","rating":5,"text":"[합성 리뷰] 예시","pros":["가벼움"],"cons":[]}]}]}
          """);
        assertDoesNotThrow(() -> ReviewFixtures.validate(valid, catalog));
        for (String rating : List.of("0", "6", "4.5", "4294967297", "\"5\"")) {
            var bad = valid.deepCopy(); ((ObjectNode)bad.at("/products/0/reviews/0")).set("rating", mapper.readTree(rating));
            assertThrows(IllegalArgumentException.class, () -> ReviewFixtures.validate(bad, catalog));
        }
        var bad = valid.deepCopy(); bad.put("updatedAt", "2026-10-23T03:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> ReviewFixtures.validate(bad, catalog));
        var unmarked = valid.deepCopy(); ((ObjectNode)unmarked.at("/products/0/reviews/0")).put("text", "실제 구매 후기");
        assertThrows(IllegalArgumentException.class, () -> ReviewFixtures.validate(unmarked, catalog));
        var unsupported = valid.deepCopy(); ((ObjectNode)unsupported.at("/products/0")).put("supported", false);
        assertThrows(IllegalArgumentException.class, () -> ReviewFixtures.validate(unsupported, catalog));
    }
}
