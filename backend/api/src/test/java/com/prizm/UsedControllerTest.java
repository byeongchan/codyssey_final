package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsedControllerTest {
    @Test void unsupportedSearchesCannotCreatePaidJobs() throws Exception {
        var client=mock(UsedClient.class); var cache=mock(LiveDataCache.class); var controller=new UsedController(client,cache); var mapper=new ObjectMapper();
        for(String json:new String[]{"{\"query\":\"폰\"}","{\"query\":\"폰\",\"regionId\":\"전국\"}","{\"query\":\"폰\",\"regionId\":\"6035\",\"page\":2}","{\"query\":\"폰\",\"regionId\":\"6035\",\"onSaleOnly\":\"true\"}"}) {
            var body=mapper.readTree(json); assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.search("karrot",body)).getStatusCode().value());
        }
        var body=mapper.readTree("{\"query\":\"폰\",\"page\":3}");
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.search("joongna",body)).getStatusCode().value());
        verifyNoInteractions(client,cache);
    }
    @Test void identicalListingIdsAndRegionsHaveSeparateCacheKeys() {
        var cache=new LiveDataCache(mock(FirebaseAccess.class),new ObjectMapper());
        try {
            var input=java.util.Map.<String,Object>of("productId","123");
            assertNotEquals(cache.key("used-joongna-detail",input),cache.key("used-karrot-detail",input));
            assertNotEquals(cache.key("used-karrot-search",java.util.Map.of("query","폰","regionId","6035")),cache.key("used-karrot-search",java.util.Map.of("query","폰","regionId","6036")));
        } finally { cache.close(); }
    }
}
