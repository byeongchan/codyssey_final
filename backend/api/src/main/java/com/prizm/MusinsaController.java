package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping(value = "/api/v1/musinsa", produces = "application/json")
public class MusinsaController {
    public static final Set<String> CATEGORIES=Set.of("001","002","003","100","103","004","017");
    private final MusinsaClient client; private final LiveDataCache cache;
    public MusinsaController(MusinsaClient client,LiveDataCache cache) { this.client=client; this.cache=cache; }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"무신사 패션·스포츠 검색 조건을 확인하세요"); }
    private void fields(JsonNode body,Set<String> allowed) {
        if(!body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(name->{if(!allowed.contains(name)) throw invalid();});
        if(body.has("refresh") && !body.path("refresh").isBoolean()) throw invalid();
    }
    private int page(JsonNode body,int max) {
        if(body.has("page") && (!body.path("page").isIntegralNumber() || !body.path("page").canConvertToInt())) throw invalid();
        int page=body.path("page").asInt(1); if(page<1 || page>max) throw invalid(); return page;
    }
    private String id(JsonNode body) { String id=body.path("productId").asText(); DanawaWishlist.validId(id); if(!body.path("productId").isTextual()) throw invalid(); return id; }
    private ResponseEntity<?> read(String action,Map<String,Object> input,boolean refresh) {
        return cache.response(cache.fetch("musinsa-"+action,input,Duration.ofDays(1),()->client.read(action,input),refresh));
    }
    @PostMapping("/search") public ResponseEntity<?> search(@RequestBody JsonNode body) {
        fields(body,Set.of("categoryId","category","query","sort","gender","page","refresh","brand","minPrice","maxPrice","color","size","minDiscount","onSale","coupon","exclusive","includeSoldOut"));
        if(!"fashion".equals(body.path("categoryId").asText()) || !CATEGORIES.contains(body.path("category").asText())) throw invalid();
        if(body.has("query") && !body.path("query").isTextual()) throw invalid();
        String query=LiveDataCache.normalize(body.path("query").asText("")),sort=body.path("sort").asText("popular"),gender=body.path("gender").asText("all");
        if(query.length()>100 || !Set.of("popular","newest","discount","price_asc","price_desc","reviews").contains(sort) || !Set.of("all","men","women").contains(gender)) throw invalid();
        var input=new LinkedHashMap<String,Object>(Map.of("categoryId","fashion","category",body.path("category").asText(),"query",query,"sort",sort,"gender",gender,"page",page(body,1000)));
        SearchFilterPolicy.prices(body,input);
        if(body.has("brand")) {
            if(!body.path("brand").isTextual() || !body.path("brand").asText().matches("[A-Za-z0-9._-]{1,80}")) throw invalid();
            input.put("brand",body.path("brand").asText());
        }
        if(body.has("color")) {
            if(!body.path("color").isTextual() || !Set.of("BLACK","WHITE","GRAY","NAVY","BEIGE","IVORY","KHAKI","SKYBLUE").contains(body.path("color").asText())) throw invalid();
            input.put("color",body.path("color").asText());
        }
        if(body.has("size")) {
            if(!body.path("size").isTextual() || !Set.of("XS","S","M","L","XL","XXL").contains(body.path("size").asText())) throw invalid();
            input.put("size",body.path("size").asText());
        }
        if(body.has("minDiscount")) {
            if(!body.path("minDiscount").isIntegralNumber() || !body.path("minDiscount").canConvertToInt() || !Set.of(10,20,30,40,50,60,70,80).contains(body.path("minDiscount").asInt())) throw invalid();
            input.put("minDiscount",body.path("minDiscount").asInt());
        }
        for(String field:List.of("onSale","coupon","exclusive","includeSoldOut")) if(body.has(field)) {
            if(!body.path(field).isBoolean()) throw invalid();
            if(body.path(field).asBoolean()) input.put(field,true);
        }
        return read("search",input,body.path("refresh").asBoolean());
    }
    @PostMapping("/product") public ResponseEntity<?> product(@RequestBody JsonNode body) {
        fields(body,Set.of("productId","refresh")); return read("product",Map.of("productId",id(body)),body.path("refresh").asBoolean());
    }
    @PostMapping("/reviews") public ResponseEntity<?> reviews(@RequestBody JsonNode body) {
        fields(body,Set.of("productId","page","refresh"));
        String id=id(body);
        // Only products already validated through the fashion adapter may expose reviews.
        cache.requireMusinsaProduct(id);
        return read("reviews",Map.of("productId",id,"page",page(body,5000)),body.path("refresh").asBoolean());
    }
    @GetMapping("/jobs/{id}") public ResponseEntity<?> job(@PathVariable("id") String id) { return cache.response(cache.musinsaJob(id)); }
}
