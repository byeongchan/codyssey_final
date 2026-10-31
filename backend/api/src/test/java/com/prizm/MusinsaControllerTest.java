package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusinsaControllerTest {
    @Test void rejectsOtherSiteCategoriesAndUnmappedSupplierCategoriesBeforeCallingPaidApis() throws Exception {
        var client=mock(MusinsaClient.class);var cache=mock(LiveDataCache.class);
        var controller=new MusinsaController(client,cache);var mapper=new ObjectMapper();
        for(String json:new String[]{"{\"categoryId\":\"pc\",\"category\":\"001\"}","{\"categoryId\":\"fashion\",\"category\":\"104\"}","{\"categoryId\":\"fashion\",\"category\":\"001\",\"page\":0}","{\"categoryId\":\"fashion\",\"category\":\"001\",\"sort\":\"unsupported\"}"}) {
            var body=mapper.readTree(json);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.search(body)).getStatusCode().value());
        }
        verifyNoInteractions(client,cache);
    }
    @Test void providerKindsCannotShareTheSameCacheIdentity() {
        var cache=new LiveDataCache(mock(FirebaseAccess.class),new ObjectMapper());
        var input=java.util.Map.of("productId","123");
        try { assertNotEquals(cache.key("product",input),cache.key("musinsa-product",input)); }
        finally { cache.close(); }
    }
    @Test void invalidDetailedFiltersCannotCreateCollectionJobs() throws Exception {
        var client=mock(MusinsaClient.class);var cache=mock(LiveDataCache.class);var mapper=new ObjectMapper();
        var controller=new MusinsaController(client,cache);
        for(String fields:new String[]{"\"minPrice\":1000,\"maxPrice\":100","\"onSale\":\"true\"","\"size\":\"260\"","\"minDiscount\":15","\"brand\":\"../nike\"","\"color\":\"unknown\""}) {
            var body=mapper.readTree("{\"categoryId\":\"fashion\",\"category\":\"001\","+fields+"}");
            assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.search(body)).getStatusCode().value());
        }
        verifyNoInteractions(client,cache);
    }
}
