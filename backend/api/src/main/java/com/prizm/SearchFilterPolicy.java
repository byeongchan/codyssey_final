package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

final class SearchFilterPolicy {
    private SearchFilterPolicy() {}
    static void prices(JsonNode body,Map<String,Object> input) {
        Long min=price(body,"minPrice"),max=price(body,"maxPrice");
        if(min!=null && max!=null && min>max) throw invalid();
        if(min!=null) input.put("minPrice",min);
        if(max!=null) input.put("maxPrice",max);
    }
    private static Long price(JsonNode body,String field) {
        if(!body.has(field)) return null;
        var value=body.path(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0 || value.asLong()>100_000_000L) throw invalid();
        return value.longValue();
    }
    static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"상세 검색 조건과 가격 범위를 확인하세요"); }
}
