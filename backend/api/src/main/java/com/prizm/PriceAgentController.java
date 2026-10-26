package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.google.cloud.firestore.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me/danawa/agent")
public class PriceAgentController {
    private final SessionVerifier sessions;
    private final FirebaseAccess firebase;
    private final LiveDataCache cache;
    private final ObjectMapper mapper;
    private final AnalysisQuota quota;
    private final PeriodPriceHistory periodHistory;
    private final String aiUrl, key, model;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PriceAgentController.class);
    @jakarta.annotation.PostConstruct public void logModel() {
        log.info("Price agent configured model={}",model.matches("gemini-[A-Za-z0-9._-]{1,100}")?model:"INVALID_OR_MISSING");
    }
    private record Daily(Map<String,Object> stats, String latest, String version, long savedAt, List<PriceAgentPolicy.Quote> quotes) {}
    private record ChatReply(JsonNode data,long expiresAt) {}
    private final Map<String,ChatReply> privateReplies=new LinkedHashMap<>();
    private final Map<String,Daily> statistics = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PriceAgentController(SessionVerifier sessions, FirebaseAccess firebase, LiveDataCache cache, ObjectMapper mapper, AnalysisQuota quota,
        String aiUrl, String key, String model) {
        this(sessions,firebase,cache,mapper,quota,aiUrl,key,model,new PeriodPriceHistory(firebase,mapper));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PriceAgentController(SessionVerifier sessions, FirebaseAccess firebase, LiveDataCache cache, ObjectMapper mapper, AnalysisQuota quota,
        @Value("${prizm.ai-url}") String aiUrl, @Value("${prizm.internal-key}") String key, @Value("${prizm.gemini-model:}") String model, PeriodPriceHistory periodHistory) {
        this.sessions=sessions; this.firebase=firebase; this.cache=cache; this.mapper=mapper; this.quota=quota; this.aiUrl=aiUrl; this.key=key; this.model=model;
        this.periodHistory=periodHistory;
    }
    private JsonNode internal(String path, Object body) {
        try {
            var uri = URI.create(aiUrl);
            if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(Objects.toString(uri.getHost(), "")) || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("", "/").contains(uri.getPath()) || key.isBlank()) throw new IllegalArgumentException();
            var request = java.net.http.HttpRequest.newBuilder(uri.resolve(path)).timeout(Duration.ofSeconds("/internal/price-agent/news".equals(path)?24:40)).header("Content-Type", "application/json")
                .header("X-Internal-Key", key).POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var stream = response.body()) { bytes = stream.readNBytes(65537); }
            if (response.statusCode()!=200 || bytes.length>65536) {
                log.warn("AI internal request failed path={} http_status={} oversized={}",path,response.statusCode(),bytes.length>65536);
                throw new IllegalArgumentException();
            }
            return mapper.readTree(bytes);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 요청이 중단되었습니다"); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스 연결·설정을 확인하세요"); }
    }
    private JsonNode saved(String action, String id) {
        var doc = PersonalData.await(firebase.firestore().collection("liveDisplayCache").document(cache.key(action, "offers".equals(action) ? Map.of("productId", id, "page", 1) : Map.of("productId", id))).get(), "분석 근거 캐시 읽기");
        try { return !doc.exists() || doc.getString("payloadJson") == null ? mapper.createObjectNode() : mapper.readTree(doc.getString("payloadJson")); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "저장 자료 형식을 확인하세요"); }
    }
    private boolean fresh(JsonNode node, long seconds) {
        try { var instant = Instant.parse(node.path("fetchedAt").asText()); return !instant.isAfter(Instant.now().plusSeconds(30)) && instant.isAfter(Instant.now().minusSeconds(seconds)); }
        catch (Exception ex) { return false; }
    }
    private synchronized Daily daily(String id, LocalDate today) {
        var root = firebase.firestore().collection("liveDanawaProducts").document(id);
        var product = PersonalData.await(root.get(), "일별 관측 버전 읽기");
        String version = id+":"+(product.exists() ? Objects.toString(product.getTimestamp("lastStoredAt"), "missing") : "missing")+":"+today;
        var hit = statistics.get(version);
        if (hit != null && System.currentTimeMillis()-hit.savedAt()<300000) return hit;
        if (!product.exists()) {
            var empty = new Daily(PriceAgentPolicy.metrics(List.of(), null, today), null, cache.key("agent-daily", List.of()), System.currentTimeMillis(), List.of());
            statistics.put(version, empty); while(statistics.size()>64) statistics.remove(statistics.keySet().iterator().next()); return empty;
        }
        var rows = PersonalData.await(root.collection("danawaDailyQuotes").whereGreaterThanOrEqualTo(FieldPath.documentId(), today.minusYears(3).toString())
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING).limit(1100).get(), "일별 가격 이력 읽기").getDocuments();
        var quotes = new ArrayList<PriceAgentPolicy.Quote>();
        for (var row : rows) {
            if (!"reef-danawa".equals(row.getString("source")) || !Boolean.FALSE.equals(row.getBoolean("synthetic"))) continue;
            Long price = row.getLong("quotedCardMinimum");
            if (price != null && price > 0 && price <= 2*DataPolicy.MAX_AMOUNT) quotes.add(new PriceAgentPolicy.Quote(LocalDate.parse(row.getId()), price));
        }
        var result = new Daily(PriceAgentPolicy.metrics(quotes, null, today), quotes.stream().map(q -> q.date().toString()).max(String::compareTo).orElse(null),
            cache.key("agent-daily", quotes.stream().map(q -> Map.of("date", q.date().toString(), "price", q.price())).toList()), System.currentTimeMillis(), List.copyOf(quotes));
        statistics.put(version, result); while(statistics.size()>64) statistics.remove(statistics.keySet().iterator().next()); return result;
    }
    private Map<String, Object> evidence(String id, String intent, String urgency) {
        var productData = saved("product", id); var product = productData.path("product");
        if (!id.equals(product.path("productId").asText())) throw new ResponseStatusException(HttpStatus.CONFLICT, "상품 상세를 먼저 조회해 주세요");
        var history = periodHistory.read(id);
        if (!history.path("priceHistory").isObject()) history=saved("price_history", id); // Existing cache remains usable before its first archive backfill.
        var offers = saved("offers", id);
        var today = LocalDate.now(DataPolicy.SEOUL);
        var daily = daily(id, today);
        Long current = null;
        for (var offer : offers.path("offers")) if ("card".equals(offer.path("payment").asText()) && offer.path("quotedTotal").isIntegralNumber()) {
            long price = offer.path("quotedTotal").asLong(); if (price > 0 && (current == null || price < current)) current = price;
        }
        boolean currentFresh = fresh(offers, 86400);
        var stats = new LinkedHashMap<>(daily.stats());
        Long low = (Long)stats.get("lowest30");
        stats.put("gapFromLowPercent", current == null || low == null ? null : (current/(double)low-1)*100);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("product", product); facts.put("productId", id); facts.put("currentCardWithShipping", current); facts.put("currentFresh", currentFresh);
        facts.put("metrics", stats);
        String basis=history.path("priceHistory").path("priceBasis").asText("UNKNOWN");
        Long comparison=null;
        if("CARD_WITH_SHIPPING".equals(basis) && currentFresh) comparison=current;
        if("CARD_WITHOUT_SHIPPING".equals(basis) && fresh(productData,86400) && product.path("lowestPrice").isIntegralNumber()) comparison=product.path("lowestPrice").asLong();
        var apiDaily=new ArrayList<PriceAgentPolicy.Quote>();
        for(var point:history.path("priceHistory").path("daily")) {
            if(point.path("lowestPrice").isIntegralNumber() && point.path("lowestPrice").asLong()>0)
                apiDaily.add(new PriceAgentPolicy.Quote(LocalDate.parse(point.path("date").asText()),point.path("lowestPrice").asLong()));
        }
        var apiStats=PriceAgentPolicy.metrics(apiDaily,comparison,today);
        boolean apiDailyUsed=(int)apiStats.get("days90")>(int)stats.get("days90") && (int)apiStats.get("days30")>=7;
        if(apiDailyUsed) {stats.clear();stats.putAll(apiStats);}
        facts.put("dailyMetricBasis",apiDailyUsed?basis:"CARD_WITH_SHIPPING");facts.put("metricCurrentPrice",apiDailyUsed?comparison:current);
        var period=PriceAgentPolicy.periodFallback(history.path("priceHistory"),today,comparison);
        boolean dailyPreferred=(int)stats.get("days30")>=7;
        if(dailyPreferred) { period.put("fallback",false);period.put("granularity",apiDailyUsed?"daily_api":"daily_observations"); }
        facts.put("periodAnalysis",period); facts.put("dailyPreferred",dailyPreferred);
        // Danawa does not verify option/stock and historical seller/shipping equivalence.
        var assessment=PurchaseTimingEngine.evaluate(id,apiDailyUsed?apiDaily:daily.quotes(),
            apiDailyUsed?comparison:current,false,apiDailyUsed?fresh(history,86400):currentFresh,urgency,today);
        assessment=PurchaseTimingEngine.withPeriodFallback(assessment,period,fresh(history,86400),today);
        if("INSUFFICIENT_DATA".equals(assessment.get("decision"))) {
            int observed=((Number)stats.get("days90")).intValue();
            int samples=((Number)period.get("sampleCount")).intValue();
            assessment.put("headline",observed>0 || samples>0?"확인된 가격 정보를 정리했습니다.":"가격 정보가 확인되면 구매 시점을 안내해 드리겠습니다.");
            assessment.put("reasoning",observed>0?List.of("최근 90일 중 "+observed+"일의 일별 가격이 관측됐습니다."):samples>0?List.of("기간별 가격 표본 "+samples+"개를 확인했습니다."):List.of());
        }
        facts.put("assessment",assessment); facts.put("verdict",assessment.get("decision"));
        facts.put("priceSignal",assessment.get("headline"));

        facts.put("periodHistory", history.path("priceHistory")); facts.put("periodHistoryFresh", fresh(history, 86400));
        // Bound context size without claiming full-market coverage.
        var sellerRows = mapper.createArrayNode(); int count=0;
        for (var offer : offers.path("offers")) { if (++count>10) break; var row=mapper.createObjectNode(); for (String field:List.of("mall","payment","price","shippingCost","quotedTotal")) row.set(field, offer.path(field)); sellerRows.add(row); }
        facts.put("sellerQuotes", sellerRows); facts.put("sellerScope", "캐시된 첫 페이지 중 최대 10개, 전체 판매처 아님");
        facts.put("dailyEvidenceVersion", daily.version());
        var times = new LinkedHashMap<String, Object>(); times.put("product", productData.path("fetchedAt").isTextual()?productData.path("fetchedAt").asText():null);
        times.put("offers", offers.path("fetchedAt").isTextual()?offers.path("fetchedAt").asText():null); times.put("periodHistory", history.path("fetchedAt").isTextual()?history.path("fetchedAt").asText():null);
        times.put("dailyLatest", apiDailyUsed?apiDaily.stream().filter(q->!q.date().isAfter(today)).map(q->q.date().toString()).max(String::compareTo).orElse(null):daily.latest()); facts.put("dataAsOf", times);
        JsonNode news = mapper.createObjectNode().put("state", "NOT_REQUESTED").put("version", "not-requested").set("articles", mapper.createArrayNode());
        if (Set.of("NEWS", "TIMING", "PRICE", "OVERVIEW").contains(intent)) {
            var catalog=PersonalData.await(firebase.firestore().collection("liveDanawaCatalog").document(id).get(),"뉴스 카테고리 읽기");
            String storedCategory=product.path("category").asText("");var categoryIds=new ArrayList<String>();
            if(catalog.exists()) {
                if(catalog.get("categoryIds") instanceof List<?> ids) for(var value:ids) if(value instanceof String text) categoryIds.add(text);
                try {String summary=catalog.getString("summaryJson");if(summary!=null) storedCategory=mapper.readTree(summary).path("category").asText(storedCategory);}
                catch(Exception ignored) { /* Classification can still use the product title. */ }
            }
            var topic=NewsCategory.resolve(storedCategory,product.path("title").asText()+" "+product.path("intro").asText(""),categoryIds);
            try {
                news=internal("/internal/price-agent/news",Map.of("category",topic==null?"":topic.category(),"keywords",topic==null?List.of():topic.keywords(),"categoryBasis",topic==null?"AUTO":topic.basis(),
                    "productTitle",product.path("title").asText(),"productId",id,"maker",product.path("maker").asText(""),"brand",product.path("brand").asText(""),"intro",product.path("intro").asText("")));

            }
            catch (ResponseStatusException ex) { news = mapper.createObjectNode().put("state", "FAILED").put("version", "failed").set("articles", mapper.createArrayNode()); }
        }
        facts.put("news", news); facts.put("statisticsDateKst", today.toString()); facts.put("policyVersion", PriceAgentPolicy.VERSION);
        return facts;
    }
    private ResponseEntity<?> direct(String message) {
        var data = new LinkedHashMap<String,Object>(); data.put("verdict", "판단 보류"); data.put("conclusion", message); data.put("priceReasons", List.of("이 응답은 가격 분석을 수행하지 않았습니다."));
        data.put("newsReasons", List.of("뉴스 조회와 AI 호출을 하지 않았습니다.")); data.put("risks", List.of()); data.put("generatedAt", Instant.now().toString()); data.put("dataAsOf", Map.of()); data.put("source", "server-rule");
        data.put("decision","INSUFFICIENT_DATA");data.put("headline",message);data.put("reasoning",List.of());data.put("wait_condition",null);data.put("data_limitations",List.of());data.put("answer",message);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Vary", "Authorization").body(Map.of("data", data, "meta", Map.of("requestId", RequestIds.current(), "source", "server-rule", "synthetic", false)));
    }
    @PostMapping("/{productId}/messages") public ResponseEntity<?> send(@RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String productId, @RequestBody JsonNode body) {
        var user = sessions.verify(token); DanawaWishlist.validId(productId);
        boolean validFields=body.isObject() && body.size()>=1 && body.size()<=3;
        for(var names=body.fieldNames();names.hasNext();) if(!Set.of("message","urgency","history").contains(names.next())) validFields=false;
        if (!validFields || !body.path("message").isTextual() || body.path("message").asText().isBlank() || body.path("message").asText().length()>1000
            || body.has("urgency") && (!body.path("urgency").isTextual() || !Set.of("URGENT","NORMAL","FLEXIBLE").contains(body.path("urgency").asText())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 1~1000자로 입력하고 구매 시급성을 확인해 주세요");
        JsonNode history=body.has("history")?body.path("history"):mapper.createArrayNode();
        if(!history.isArray() || history.size()>6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"대화 문맥 형식을 확인하세요");
        for(var row:history) if(!row.isObject() || row.size()!=2 || !Set.of("user","assistant").contains(row.path("role").asText())
            || !row.path("content").isTextual() || row.path("content").asText().isBlank() || row.path("content").asText().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"대화 문맥 형식을 확인하세요");
        String question = body.path("message").asText();
        if ("OFF_TOPIC".equals(PriceAgentPolicy.intent(question, null))) return direct(PriceAgentPolicy.OFF_TOPIC);
        var root = firebase.firestore().collection("users").document(user.uid()); var context = root.collection("priceAgentContexts").document(productId);
        var old = PersonalData.await(context.get(), "채팅 문맥 읽기");
        String previous = old.exists() ? old.getString("lastIntent") : null;
        if (!old.exists() || old.getLong("expiresAt") == null || old.getLong("expiresAt") < System.currentTimeMillis()) previous = null;
        String intent = PriceAgentPolicy.intent(question, previous);
        if ("CLARIFY".equals(intent)) return direct("현재 선택한 상품의 가격, 구매 시점, 사양, 판매처 또는 뉴스 중 어떤 내용이 궁금하신가요?");
        PersonalData.await(firebase.firestore().runTransaction(tx -> {
            PersonalData.active(tx, root); tx.set(context, Map.of("lastIntent", intent, "expiresAt", System.currentTimeMillis()+1800000)); return true;
        }), "채팅 문맥 저장");
        var facts = evidence(productId, intent, body.path("urgency").asText("NORMAL"));
        String version = cache.key("agent-evidence", facts);
        // Personalized answers never enter shared Firestore caches. Only a bounded private process cache is used.
        String replyKey=cache.key("private-agent-v1",Map.of("owner",user.uid(),"productId",productId,"intent",intent,"evidence",version,
            "question",question,"history",history,"model",model,"promptVersion","agent-prompt-v12"));
        synchronized(privateReplies) {
            var hit=privateReplies.get(replyKey);
            if(hit!=null && hit.expiresAt()>System.currentTimeMillis()) return chatResponse(hit.data(),true);
        }
        JsonNode reply=answer(user.uid(),productId,intent,version,facts,question,history);
        if (!reply.path("aiUnavailable").asBoolean()) synchronized(privateReplies) {
            privateReplies.put(replyKey,new ChatReply(reply.deepCopy(),System.currentTimeMillis()+300000));
            while(privateReplies.size()>128) privateReplies.remove(privateReplies.keySet().iterator().next());
        }
        return chatResponse(reply,false);
    }
    private ResponseEntity<?> chatResponse(JsonNode data,boolean hit) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Vary","Authorization")
            .body(Map.of("data",data,"meta",Map.of("requestId",RequestIds.current(),"source",data.path("source").asText(),"synthetic",false,"cacheHit",hit)));
    }

    private JsonNode answer(String uid, String id, String intent, String version, Map<String,Object> facts, String question, JsonNode history) {
        var result = mapper.createObjectNode(); result.put("productId", id); result.put("verdict", "판단 보류");
        result.put("priceSignal", facts.get("priceSignal").toString()); result.set("metrics", mapper.valueToTree(facts.get("metrics"))); result.set("dataAsOf", mapper.valueToTree(facts.get("dataAsOf")));
        result.set("currentPrice", mapper.valueToTree(facts.get("metricCurrentPrice")));result.put("dailyMetricBasis",facts.get("dailyMetricBasis").toString());
        result.set("periodAnalysis", mapper.valueToTree(facts.get("periodAnalysis")));
        result.set("news", mapper.valueToTree(facts.get("news"))); result.put("evidenceVersion", version); result.put("generatedAt", Instant.now().toString()); result.put("source", "server-rule");
        var assessment=mapper.valueToTree(facts.get("assessment"));
        for(String field:List.of("decision","headline","reasoning","risks","wait_condition","data_limitations")) result.set(field,assessment.path(field));
        result.put("answer",assessment.path("headline").asText()+" "
            +String.join(" ", mapper.convertValue(assessment.path("reasoning"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){})));
        result.set("verdict",result.path("decision")); result.set("conclusion",result.path("answer")); result.set("priceReasons",result.path("reasoning"));
        result.set("analysis",assessment);
        result.set("newsReasons",mapper.valueToTree(newsReasons(mapper.valueToTree(facts.get("news")))));
        result.put("intent",intent);
        result.put("showDecision",question.matches("(?s).*(구매|사도|살까|사야|기다|언제사|언제살).*"));
        var product=(JsonNode)facts.get("product");
        if("NEWS".equals(intent)) result.put("answer",String.join(" ",newsReasons(mapper.valueToTree(facts.get("news")))));
        if("PRICE".equals(intent) && !result.path("showDecision").asBoolean()) {
            var period=result.path("periodAnalysis");
            var change=period.path("periodChangePercent");
            result.put("answer",period.path("sampleCount").asInt()>0 && change.isNumber()
                ? "확인된 가격 기록("+period.path("coverageStart").asText()+" ~ "+period.path("coverageEnd").asText()+")에서 첫 표본 대비 마지막 표본은 "
                    +change.asText()+"% 변했습니다. 기간별 최저가 표본 기준이며 실제 결제 가격과 다를 수 있습니다."
                : "현재 확인된 가격 기록만으로 가격 흐름을 설명하기 어렵습니다.");
        }
        if("SPECS".equals(intent)) result.put("answer",product.path("title").asText()+" · "+product.path("intro").asText("상품 정보에 명시된 내용으로 안내해 드리겠습니다."));
        if("SELLERS".equals(intent)) result.put("answer","확인된 판매처 가격과 배송 조건을 기준으로 비교해 드리겠습니다.");
        result.set("conclusion",result.path("answer"));
        if(product.path("title").asText().isBlank()) return result;
        if(!model.matches("gemini-[A-Za-z0-9._-]{1,100}")) {result.put("aiUnavailable",true);return result;}
        try {
            quota.consume(uid);
            var generated = internal("/internal/price-agent/analyze", Map.of("productId", id, "intent", intent, "evidenceVersion", version, "model", model, "evidence", facts,"question",question,"history",history));
            if (!id.equals(generated.path("productId").asText()) || !version.equals(generated.path("evidenceVersion").asText()) || !model.equals(generated.path("model").asText())
                || !result.path("decision").equals(generated.path("decision")) || !result.path("wait_condition").equals(generated.path("wait_condition"))) throw new IllegalArgumentException();
            for(String field:List.of("headline","answer")) if(!generated.path(field).isTextual() || generated.path(field).asText().isBlank() || generated.path(field).asText().length()>2000) throw new IllegalArgumentException();
            for(String field:List.of("reasoning","risks","data_limitations")) {
                if(!generated.path(field).isArray() || generated.path(field).size()>8) throw new IllegalArgumentException();
                for(var text:generated.path(field)) if(!text.isTextual() || text.asText().isBlank() || text.asText().length()>300) throw new IllegalArgumentException();
            }
            for(String field:List.of("headline","answer","reasoning","risks","data_limitations")) result.set(field,generated.path(field));
            result.set("conclusion",result.path("answer"));result.set("priceReasons",result.path("reasoning"));
            result.put("source", "gemini"); result.put("model", model);
        } catch (Exception ex) {
            log.warn("Price agent fallback model={} error_type={}",model,ex.getClass().getSimpleName());
            result.put("aiUnavailable", true); result.set("conclusion",result.path("answer"));
        }
        return result;
    }
    @GetMapping("/jobs/{jobId}") public ResponseEntity<?> job(@RequestHeader(value="Authorization", defaultValue="") String token, @PathVariable String jobId) {
        sessions.verify(token); return cache.response(cache.agentJob(jobId));
    }
    static List<String> newsReasons(JsonNode news) {
        int count=news.path("articles").isArray()?news.path("articles").size():0;
        String state=news.path("state").asText();
        if(count>0) return List.of("가격 영향 경로를 검토한 뉴스 "+count+"건을 확인했습니다. 근거 기사와 참고 기사를 구분해 활용합니다."
            +("PARTIAL".equals(state)?" 일부 검색을 완료하지 못해 확인 범위가 제한됩니다.":""));
        String message=switch(state) {
            case "READY" -> "최근 관련 뉴스 없음";
            case "NOT_REQUESTED" -> "이 질문에서는 뉴스 검색을 하지 않았습니다.";
            case "NOT_CONFIGURED" -> "뉴스 검색 설정이 필요합니다.";
            case "CATEGORY_UNKNOWN", "IDENTIFICATION_UNCERTAIN" -> "상품 정보를 자동으로 분류했지만 상품군을 특정할 근거가 명확하지 않아 뉴스 검색은 생략했습니다.";
            case "AUTH_FAILED" -> "뉴스 검색 인증·권한을 확인하지 못했습니다.";
            case "API_NOT_FOUND" -> "뉴스 검색 API 주소를 확인하지 못했습니다.";
            case "INVALID_REQUEST" -> "뉴스 검색 요청 형식을 확인해 주세요.";
            case "REQUEST_REJECTED" -> "뉴스 검색 요청이 거부되었습니다. API 이용 설정을 확인해 주세요.";
            case "RATE_LIMITED" -> "뉴스 검색 요청 한도로 확인하지 못했습니다.";
            default -> "뉴스 검색을 완료하지 못했습니다.";
        };
        var diagnostics=news.path("diagnostics");
        int status=diagnostics.path("httpStatus").asInt();
        String code=diagnostics.path("errorCode").asText();
        if(!Set.of("READY","NOT_REQUESTED","NOT_CONFIGURED").contains(state) && status>=300 && status<=599)
            message+=" (HTTP "+status+(code.matches("[0-9]{3}|SE[0-9]{2}")?" / 코드 "+code:"")+")";
        return List.of(message);
    }
}
