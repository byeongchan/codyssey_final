package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceObservationsTest {
    @Test void excludesUnknownShippingStaleSoldOutAndFutureOffers() throws Exception {
        var mapper = new ObjectMapper(); var catalog = mock(CatalogRepository.class);
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\"}")));
        var offers = mapper.readTree("""
                [{"productPrice":100,"shippingFee":null,"unconditionalDiscount":0,"availability":"IN_STOCK","currency":"KRW","observedAt":"2026-10-22T03:00:00Z"},
                 {"productPrice":200,"shippingFee":0,"unconditionalDiscount":0,"availability":"IN_STOCK","currency":"KRW","observedAt":"2026-10-20T03:00:00Z"},
                 {"productPrice":300,"shippingFee":0,"unconditionalDiscount":0,"availability":"OUT_OF_STOCK","currency":"KRW","observedAt":"2026-10-22T03:00:00Z"},
                 {"productPrice":400,"shippingFee":0,"unconditionalDiscount":0,"availability":"IN_STOCK","currency":"KRW","observedAt":"2026-10-23T03:00:00Z"},
                 {"productPrice":5000,"shippingFee":3000,"unconditionalDiscount":0,"availability":"IN_STOCK","currency":"KRW","observedAt":"2026-10-22T03:00:00Z"}]
                """);
        var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>(); offers.forEach(rows::add);
        when(catalog.offers("p")).thenReturn(rows);
        assertEquals(Long.valueOf(8000), new PriceObservations(catalog).current("p").price());
        assertFalse(new PriceObservations(catalog).current("missing").available());
    }
}
