package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SearchFilterPolicyTest {
    @Test void rejectsInvalidAndReversedRangesBeforeExternalCalls() throws Exception {
        var mapper=new ObjectMapper();
        for(String json:new String[]{"{\"minPrice\":-1}","{\"maxPrice\":100000001}","{\"minPrice\":\"100\"}","{\"minPrice\":1.5}","{\"maxPrice\":null}","{\"minPrice\":200,\"maxPrice\":100}"}) {
            var body=mapper.readTree(json);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->SearchFilterPolicy.prices(body,new java.util.HashMap<>())).getStatusCode().value());
        }
        var input=new java.util.HashMap<String,Object>();
        SearchFilterPolicy.prices(mapper.readTree("{\"minPrice\":0,\"maxPrice\":1000}"),input);
        assertEquals(java.util.Map.of("minPrice",0L,"maxPrice",1000L),input);
    }
}
