package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative title/model rules. A model group is not an identical-condition or physical-item group. */
final class UsedProductGroups {
    record Group(String kind,String name) {}
    private static final String[][] MODELS={
        {"아이폰","(?:아이폰|iphone)\\s*(se\\s*(?:[1-4]세대|[1-4])?|\\d{1,2})\\s*(pro\\s*max|프로\\s*맥스|pro|프로|plus|플러스|mini|미니|air|에어)?"},
        {"갤럭시","(?:갤럭시|galaxy)\\s*(s\\s*\\d{1,2}|a\\s*\\d{1,2}|z?\\s*(?:폴드|fold|플립|flip)\\s*\\d|노트\\s*\\d{1,2}|note\\s*\\d{1,2})\\s*(ultra|울트라|plus|플러스|fe|\\+)?"},
        {"아이패드","(?:아이패드|ipad)\\s*(pro|프로|air|에어|mini|미니)?\\s*(\\d{1,2}(?:\\.\\d)?\\s*(?:세대|인치)|m[1-9])"},
        {"맥북","(?:맥북|macbook)\\s*(pro|프로|air|에어)\\s*(m[1-9]|20\\d{2})"},
        {"에어팟","(?:에어팟|airpods)\\s*(pro|프로|max|맥스)?\\s*([1-4](?:세대)?)?"},
        {"플레이스테이션","(?:플레이스테이션|playstation|ps)\\s*([345])\\s*(pro|프로|slim|슬림)?"},
        {"닌텐도 스위치","(?:닌텐도\\s*스위치|nintendo\\s*switch)\\s*(2|oled|라이트|lite)?"},
        {"그래픽카드","(?:geforce\\s*)?((?:rtx|gtx|rx)\\s*\\d{3,4})\\s*(ti\\s*super|super|ti|xtx|xt)?"}
    };
    private static final String[][] CATEGORIES={
        {"휴대폰 케이스","(?:아이폰|갤럭시|휴대폰|핸드폰|스마트폰).*케이스|폰케이스"},
        {"교체 부품","교체\\s*부품|액정\\s*(?:부품|만)|메인보드"},
        {"빈 상자","빈\\s*(?:상자|박스)|(?:상자|박스)\\s*만\\s*(?:판매|팝)"},
        {"책상","책상|데스크(?!탑)"},{"의자","의자|체어"},{"소파","소파|쇼파"},{"수납장","수납장|서랍장"},
        {"식탁","식탁"},{"침대","침대|매트리스"},{"책장","책장"},{"옷장","옷장|행거"},
        {"자전거","자전거"},{"유모차","유모차"},{"모니터","모니터"},{"노트북","노트북"},
        {"냉장고","냉장고"},{"세탁기","세탁기"},{"청소기","청소기"},{"에어컨","에어컨"},
        {"카메라","카메라"},{"렌즈","렌즈"},{"키보드","키보드"},{"마우스","마우스"},
        {"운동화","운동화|스니커즈"},{"가방","가방|백팩"},{"티셔츠","티셔츠"},{"바지","바지|청바지"}
    };
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("프로","pro").replace("맥스","max").replace("플러스","plus")
            .replace("울트라","ultra").replace("미니","mini").replace("에어","air").replace("슬림","slim")
            .replace("라이트","lite").replace("폴드","fold").replace("플립","flip").replace("노트","note")
            .replace("+","plus").replaceAll("\\s+","").replace("세대","").replaceAll("^z(?=fold|flip)","");
    }
    static Group identify(JsonNode row) {
        String title=UsedListingFacts.clean(row.path("title").asText()).toLowerCase(Locale.ROOT);
        // A compatible-model name on an accessory is not the actual item being sold.
        for(int i=0;i<3;i++) if(Pattern.compile(CATEGORIES[i][1]).matcher(title).find()) return new Group("CATEGORY",CATEGORIES[i][0]);
        if(title.matches(".*(호환|케이스|보호필름|충전케이블|충전 케이블).*")) return new Group("CATEGORY","액세서리");
        var detected=new LinkedHashSet<String>();
        for(var rule:MODELS) {
            var match=Pattern.compile("(?i)(?<![a-z0-9])"+rule[1]+"(?![a-z0-9])").matcher(title);
            while(match.find()) {
                StringBuilder name=new StringBuilder(rule[0]);
                for(int i=1;i<=match.groupCount();i++) if(match.group(i)!=null && !match.group(i).isBlank()) name.append(' ').append(normalize(match.group(i)));
                detected.add(name.toString());
            }
        }
        if(detected.size()==1) return new Group("MODEL",detected.iterator().next());
        if(detected.size()>1) return null; // Multiple advertised models need review, not arbitrary assignment.
        String body=UsedListingFacts.clean(row.path("description").asText());
        var labelled=Pattern.compile("(?im)^\\s*(?:모델명|모델|품번)\\s*[:：]\\s*([^\\n;|]{2,80})").matcher(body);
        if(labelled.find()) {
            String name=labelled.group(1).strip().replaceAll("(?i)\\b\\d+(?:gb|tb)\\b","").replaceAll("\\s+"," ").strip();
            if(!name.isBlank()) {
                // Reuse the same aliases for an explicit body model as for a title model.
                var probe=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("title",name);
                var canonical=identify(probe);
                return canonical!=null && canonical.kind().equals("MODEL")?canonical:new Group("MODEL",name.toUpperCase(Locale.ROOT));
            }
        }
        var branded=Pattern.compile("(?i)(소니|sony|삼성|samsung|lg|엘지|다이슨|dyson|캐논|canon|니콘|nikon|로지텍|logitech)\\s+([a-z]{1,8}[-]?[0-9]{1,6}[a-z0-9-]*)(?![a-z0-9])").matcher(title);
        if(branded.find()) {
            return new Group("MODEL",branded.group(2).toUpperCase(Locale.ROOT));
        }
        // Recognizable alphanumeric manufacturer model codes (preserve suffixes and hyphens).
        var code=Pattern.compile("(?i)(?<![a-z0-9])([a-z]{1,8}[-]?[0-9]{2,6}[a-z][a-z0-9-]*|[0-9]{2}[a-z]{1,4}[0-9]{2,5}[a-z0-9-]*)(?![a-z0-9])").matcher(title);
        var codes=new LinkedHashSet<String>(); while(code.find()) codes.add(code.group(1).toUpperCase(Locale.ROOT));
        if(codes.size()==1) return new Group("MODEL",codes.iterator().next());
        for(int i=3;i<CATEGORIES.length;i++) if(Pattern.compile(CATEGORIES[i][1]).matcher(title).find()) return new Group("CATEGORY",CATEGORIES[i][0]);
        String category=row.path("categoryName").asText("").strip();
        if(!category.isBlank() && !Set.of("기타","전체","unknown").contains(category.toLowerCase(Locale.ROOT)))
            return new Group("CATEGORY",category.substring(0,Math.min(60,category.length())));
        return null;
    }
    static ObjectNode statistics(ObjectMapper mapper,Group group,List<JsonNode> rows) {
        var eligible=new ArrayList<JsonNode>();
        for(var row:rows) if(!Pattern.compile("고장|파손|부품용|수리용|삽니다|구합니다|대여|예약금|보증금|일괄|묶음").matcher(row.path("title").asText()).find()) eligible.add(row);
        var stats=UsedSearchAnalysis.assess(mapper,eligible,1);
        var result=mapper.createObjectNode(); result.put("kind",group.kind()); result.put("name",group.name()); result.put("count",rows.size());
        result.put("analyzedCount",stats.path("analyzedCount").asInt()); result.put("excludedCount",rows.size()-stats.path("analyzedCount").asInt());
        result.put("unknownStatusCount",stats.path("unknownStatusCount").asInt());
        result.put("smallSample",stats.path("analyzedCount").asInt()<5);
        for(String field:List.of("low","average","high","median","centralLow","centralHigh")) if(stats.has(field)) result.set(field,stats.path(field));
        var dominant=result.putArray("dominantRanges");
        for(var bin:stats.path("distribution")) if(bin.path("dominant").asBoolean()) dominant.add(bin.deepCopy());
        result.put("basis",group.kind().equals("MODEL")?"같은 모델명 · 용량·색상·구성·상태 차이 포함":"같은 상품 종류 · 브랜드·모델·규격·상태 차이 포함");
        return result;
    }
    static ObjectNode assess(ObjectMapper mapper,List<JsonNode> rows) {
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var row:rows) unique.putIfAbsent(row.path("provider").asText()+":"+row.path("productId").asText(),row);
        var groups=new LinkedHashMap<Group,List<JsonNode>>(); int unclassified=0;
        for(var row:unique.values()) {var group=identify(row); if(group==null) unclassified++; else groups.computeIfAbsent(group,k->new ArrayList<>()).add(row);}
        var result=mapper.createObjectNode(); result.put("total",unique.size()); result.put("unclassifiedCount",unclassified); result.put("groupCount",groups.size());
        var values=result.putArray("groups");
        groups.entrySet().stream().sorted(Comparator.<Map.Entry<Group,List<JsonNode>>>comparingInt(e->e.getValue().size()).reversed().thenComparing(e->e.getKey().name()))
            .limit(40).forEach(e->values.add(statistics(mapper,e.getKey(),e.getValue())));
        result.put("omittedGroupCount",Math.max(0,groups.size()-40));
        result.put("basis","제목의 모델명·모델번호 또는 상품 종류 기준 자동 분류. 게시글 ID 중복만 제거했으며 동일 실물 중복은 검증하지 않았습니다. 분류 불명확한 상품은 별도 집계합니다.");
        return result;
    }
    static ObjectNode selected(ObjectMapper mapper,JsonNode selected,List<JsonNode> rows) {
        var original=rows.stream().filter(row->row.path("provider").equals(selected.path("provider")) && row.path("productId").equals(selected.path("productId"))).findFirst().orElse(selected);
        var group=identify(original);
        if(group==null) return mapper.createObjectNode();
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var row:rows) if(group.equals(identify(row)) && !(row.path("provider").equals(selected.path("provider")) && row.path("productId").equals(selected.path("productId"))))
            unique.putIfAbsent(row.path("provider").asText()+":"+row.path("productId").asText(),row);
        var result=statistics(mapper,group,new ArrayList<>(unique.values()));
        result.put("selectedExcluded",true);
        return result;
    }
}
