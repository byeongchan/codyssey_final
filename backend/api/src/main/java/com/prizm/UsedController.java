package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping(value = "/api/v1/used", produces = "application/json")
public class UsedController {
    private final UsedClient client; private final LiveDataCache cache;
    public UsedController(UsedClient client, LiveDataCache cache) { this.client=client; this.cache=cache; }
    static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"중고 검색 조건·지역을 확인하세요"); }
    static void provider(String provider) { if(!Set.of("joongna","karrot").contains(provider)) throw invalid(); }
    static void id(String id) { if(!id.matches("[A-Za-z0-9_-]{1,100}")) throw invalid(); }
    private String text(JsonNode body,String key,int max,boolean required) {
        if(!body.has(key) && !required) return "";
        if(!body.path(key).isTextual()) throw invalid();
        String value=LiveDataCache.normalize(body.path(key).asText()); if(value.length()>max || required && value.isBlank()) throw invalid(); return value;
    }
    private void fields(JsonNode body,Set<String> allowed) {
        if(!body.isObject()) throw invalid(); body.fieldNames().forEachRemaining(name->{ if(!allowed.contains(name)) throw invalid(); });
        if(body.has("refresh") && !body.path("refresh").isBoolean()) throw invalid();
    }
    private ResponseEntity<?> read(String provider,String action,Map<String,Object> input,boolean refresh) {
        return cache.response(cache.fetch("used-"+provider+"-"+action,input,Duration.ofDays(action.equals("search") || action.equals("detail")?1:7),()->client.read(provider,action,input),refresh));
    }
    @PostMapping("/{provider}/search") public ResponseEntity<?> search(@PathVariable("provider") String provider,@RequestBody JsonNode body) {
        provider(provider); fields(body,Set.of("query","regionId","categoryId","page","onSaleOnly","refresh"));
        var input=new LinkedHashMap<String,Object>(); input.put("query",text(body,"query",100,true));
        if(provider.equals("joongna")) {
            if(body.has("regionId") || body.has("categoryId") || body.has("onSaleOnly")) throw invalid();
            if(body.has("page") && (!body.path("page").isIntegralNumber() || !body.path("page").canConvertToInt())) throw invalid();
            int page=body.path("page").asInt(1); if(page<1 || page>2) throw invalid(); input.put("page",page);
        } else {
            String region=text(body,"regionId",20,true); if(!region.matches("[0-9]{1,20}") || body.has("page")) throw invalid(); input.put("regionId",region);
            if(body.has("categoryId")) { if(!body.path("categoryId").isIntegralNumber() || !body.path("categoryId").canConvertToInt() || body.path("categoryId").asInt()<1 || body.path("categoryId").asInt()>10000) throw invalid(); input.put("categoryId",body.path("categoryId").asInt()); }
            if(body.has("onSaleOnly")) { if(!body.path("onSaleOnly").isBoolean()) throw invalid(); input.put("onSaleOnly",body.path("onSaleOnly").asBoolean()); }
        }
        return read(provider,"search",input,body.path("refresh").asBoolean());
    }
    @PostMapping("/{provider}/detail") public ResponseEntity<?> detail(@PathVariable("provider") String provider,@RequestBody JsonNode body) {
        provider(provider); fields(body,Set.of("productId","refresh")); String id=text(body,"productId",100,true); id(id);
        return read(provider,"detail",Map.of("productId",id),body.path("refresh").asBoolean());
    }
    @PostMapping("/karrot/regions") public ResponseEntity<?> regions(@RequestBody JsonNode body) {
        fields(body,Set.of("regionId","query")); var input=new LinkedHashMap<String,Object>();
        String region=text(body,"regionId",20,false),query=text(body,"query",60,false);
        if(!region.isBlank()) { if(!region.matches("[0-9]{1,20}")) throw invalid(); input.put("regionId",region); }
        if(!query.isBlank()) input.put("query",query); return read("karrot","regions",input,false);
    }
    @PostMapping("/karrot/categories") public ResponseEntity<?> categories(@RequestBody JsonNode body) { fields(body,Set.of()); return read("karrot","categories",Map.of(),false); }
    @GetMapping("/jobs/{id}") public ResponseEntity<?> job(@PathVariable("id") String id) { return cache.response(cache.usedJob(id)); }
}
