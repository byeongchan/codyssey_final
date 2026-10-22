package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DataPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode catalog() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"schemaVersion":1,"referenceTime":"2026-10-22T03:00:00Z",
                 "categories":[{"id":"audio","name":"음향"}],
                 "products":[{"id":"p","title":"상품","categoryId":"audio","optionKey":"black-new"}]}
                """);
    }
    private ObjectNode prices() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"offers":[{"id":"o","productId":"p","store":"더미","currency":"KRW",
                  "productPrice":10000,"shippingFee":null,"unconditionalDiscount":0,
                  "availability":"IN_STOCK","conditionalBenefits":[],"observedAt":"2026-10-22T03:00:00Z"}],
                 "dailyPrices":[{"productId":"p","date":"2026-10-22","comparablePrice":10000,"validSamples":1}]}
                """);
    }
    @Test void missingShippingValueIsDifferentFromUnknownShipping() throws Exception {
        ObjectNode data = prices();
        assertDoesNotThrow(() -> DataPolicy.validate(catalog(), data));
        ((ObjectNode) data.path("offers").get(0)).remove("shippingFee");
        assertThrows(IllegalArgumentException.class, () -> DataPolicy.validate(catalog(), data));
    }
    @Test void rejectsNegativeFractionalAndExcessivePrices() throws Exception {
        for (String value : new String[]{"-1", "0", "10.5", "1000000000001", "\"10000\"", "null", "true"}) {
            ObjectNode data = prices();
            ((ObjectNode) data.path("offers").get(0)).set("productPrice", mapper.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> DataPolicy.validate(catalog(), data), value);
        }
    }
    @Test void rejectsDiscountThatExceedsProductPrice() throws Exception {
        ObjectNode data = prices();
        ((ObjectNode) data.path("offers").get(0)).put("unconditionalDiscount", 10001);
        assertThrows(IllegalArgumentException.class, () -> DataPolicy.validate(catalog(), data));
    }
    @Test void rejectsDuplicateDailySamplesRatherThanInflatingObservationCount() throws Exception {
        ObjectNode data = prices();
        ((com.fasterxml.jackson.databind.node.ArrayNode) data.path("dailyPrices")).add(data.path("dailyPrices").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> DataPolicy.validate(catalog(), data));
    }
    @Test void rejectsFutureAndTimezoneLessObservations() throws Exception {
        for (String timestamp : new String[]{"2026-10-22T03:00:01Z", "2026-10-22T03:00:00", "invalid"}) {
            ObjectNode data = prices();
            ((ObjectNode) data.path("offers").get(0)).put("observedAt", timestamp);
            assertThrows(IllegalArgumentException.class, () -> DataPolicy.validate(catalog(), data));
        }
    }
    @Test void expiresOnlyAfterTwentyFourHours() {
        Instant observed = Instant.parse("2026-10-22T03:00:00Z");
        assertFalse(DataPolicy.stale(observed, observed.plusSeconds(86400)));
        assertTrue(DataPolicy.stale(observed, observed.plusSeconds(86401)));
    }
    @Test void dailyExpiryUsesKoreanCalendarAndEndOfDay() {
        assertFalse(DataPolicy.historyStale("2026-10-21", Instant.parse("2026-10-22T03:00:00Z")));
        assertTrue(DataPolicy.historyStale("2026-10-20", Instant.parse("2026-10-22T03:00:00Z")));
    }
}
