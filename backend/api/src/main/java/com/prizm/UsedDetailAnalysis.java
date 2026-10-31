package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Source-first detail policy. Never promotes a broad search price pool to a matched comparison. */
final class UsedDetailAnalysis {
    static final String VERSION="used-detail-document-v2";
    private record Row(JsonNode item,ObjectNode facts) {}
    static ObjectNode assess(ObjectMapper mapper,JsonNode detail,List<JsonNode> input) {
        var selected=detail.path("product"); String time=detail.path("fetchedAt").asText("");
        var facts=UsedListingFacts.read(mapper,selected,time);
        var unique=new LinkedHashMap<String,JsonNode>();
        String own=identity(selected);
        for(var row:input) if(!own.equals(identity(row))) unique.putIfAbsent(identity(row),row);
        var report=mapper.createObjectNode(); var counts=report.putObject("counts");
        counts.put("observed",input.size()); counts.put("deduplicated",unique.size()); counts.put("duplicateObservations",input.size()-unique.size());
        var comparable=new ArrayList<Row>(); var exclusions=new TreeMap<String,Integer>(); int conditions=0,validPrices=0,details=0;
        var excludedExamples=mapper.createArrayNode();
        for(var row:unique.values()) {
            var classified=UsedListingFacts.read(mapper,row,row.path("observedAt").asText(""));
            if(classified.path("hasDetail").asBoolean()) details++;
            boolean match=facts.path("conditionsComplete").asBoolean() && classified.path("conditionsComplete").asBoolean()
                && facts.path("conditionSignature").asText().equals(classified.path("conditionSignature").asText());
            if(match) conditions++;
            if(classified.path("validPrice").asBoolean()) validPrices++;
            String reason=!classified.path("hasDetail").asBoolean()?"본문·조건 근거 미확인"
                :!match?"핵심 사양·상태·구성·판매 단위 일치 미확인"
                :!classified.path("validPrice").asBoolean()?"판매 의도·전체 제시가 미확인 또는 비교 부적격"
                :!"on_sale".equals(classified.path("status").asText())?"현재 판매 상태 미확인·예약·판매 완료"
                :!classified.path("fresh24h").asBoolean()?"가격·상태 확인 24시간 초과 또는 시각 미확인":"";
            if(reason.isEmpty()) comparable.add(new Row(row,classified));
            else {
                exclusions.merge(reason,1,Integer::sum);
                if(excludedExamples.size()<5) {var example=excludedExamples.addObject(); example.put("provider",row.path("provider").asText()); example.put("productId",row.path("productId").asText()); example.put("reason",reason); example.set("priceIssues",classified.path("reasons"));}
            }
        }
        counts.put("detailsAvailable",details); counts.put("conditionMatched",conditions); counts.put("validPriceObserved",validPrices);
        counts.put("statisticsUsed",comparable.size()); counts.put("recommended",0); counts.put("independenceVerified",false);
        var reasons=mapper.createArrayNode(); for(var entry:exclusions.entrySet()) {var reason=reasons.addObject(); reason.put("reason",entry.getKey()); reason.put("count",entry.getValue());}
        report.set("exclusionReasons",reasons); report.set("excludedExamples",excludedExamples);
        List<Long> prices=comparable.stream().map(row->row.facts.path("askingPrice").asLong()).toList();
        double[] sorted=prices.stream().mapToDouble(Long::doubleValue).sorted().toArray();
        var comparison=report.putObject("comparison"); comparison.put("basis","같은 명시 사양·상태·구성·판매 단위의 게시된 상품 판매 희망가");
        comparison.put("count",prices.size()); comparison.put("kind","ASKING_PRICE");
        var sellerCounts=new HashMap<String,Integer>(); int sellerUnknown=0;
        for(var row:comparable) {
            String seller=row.item.path("sellerKey").asText("");
            if(seller.isBlank()) sellerUnknown++; else sellerCounts.merge(seller,1,Integer::sum);
        }
        comparison.put("knownSellerCount",sellerCounts.size()); comparison.put("sellerUnknownListings",sellerUnknown);
        comparison.put("sellerIdentityBasis","플랫폼의 공개 판매자 ID를 플랫폼별로 해시한 식별값 기준입니다. 닉네임으로 추정하지 않으며 실제 사람 수·실물 독립성의 검증은 아닙니다.");
        double maxSellerShare=prices.isEmpty()?0:sellerCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0)*100.0/prices.size();
        comparison.put("maxKnownSellerSharePercent",maxSellerShare);
        if(sellerUnknown==0 && sellerCounts.size()>=2 && prices.size()>=5) {
            var bySeller=new HashMap<String,List<Double>>();
            for(var row:comparable) bySeller.computeIfAbsent(row.item.path("sellerKey").asText(),ignored->new ArrayList<>()).add(row.facts.path("askingPrice").asDouble());
            double[] medians=bySeller.values().stream().mapToDouble(values->UsedRobustStatistics.q(values.stream().mapToDouble(Double::doubleValue).sorted().toArray(),.5)).sorted().toArray();
            comparison.put("sellerMedianOfMedians",UsedRobustStatistics.q(medians,.5));
        }
        var platformCounts=new TreeMap<String,Integer>(); for(var row:comparable) platformCounts.merge(row.item.path("provider").asText(),1,Integer::sum);
        comparison.set("platformCounts",mapper.valueToTree(platformCounts));
        if(!comparable.isEmpty()) {
            var times=comparable.stream().map(row->Instant.parse(row.facts.path("observedAt").asText())).sorted().toList();
            comparison.put("observedFrom",times.get(0).toString()); comparison.put("observedTo",times.get(times.size()-1).toString());
        }
        long signalPrice=facts.path("validPrice").asBoolean() && facts.path("conditionsComplete").asBoolean() && facts.path("fresh24h").asBoolean()?facts.path("askingPrice").asLong():0;
        var outlier=UsedRobustStatistics.signal(mapper,signalPrice,prices); report.set("outlier",outlier);
        var flagged=new ArrayList<Long>();
        for(int i=0;i<prices.size();i++) {
            var peers=new ArrayList<>(prices); long current=peers.remove(i);
            if(UsedRobustStatistics.signal(mapper,current,peers).path("flagged").asBoolean()) flagged.add(current);
        }
        comparison.put("reviewSignalCount",flagged.size());
        // No numeric-only exclusion. Show sensitivity, without relabelling it as corrected truth.
        if(sorted.length>=5) {
            comparison.put("median",UsedRobustStatistics.q(sorted,.5)); comparison.put("q25",UsedRobustStatistics.q(sorted,.25)); comparison.put("q75",UsedRobustStatistics.q(sorted,.75));
            comparison.put("low",sorted[0]); comparison.put("high",sorted[sorted.length-1]);
            var remaining=new ArrayList<>(prices); for(Long price:flagged) remaining.remove(price);
            if(!flagged.isEmpty() && remaining.size()>=5) comparison.put("medianWithoutSignals",UsedRobustStatistics.q(remaining.stream().mapToDouble(Long::doubleValue).sorted().toArray(),.5));
        }
        boolean duplicateSuspected=false;
        var signatures=new HashSet<String>();
        for(var row:comparable) {
            String body=row.item.path("description").asText("").strip(),image=row.item.path("image").asText(""),region=row.item.path("regionName").asText("");
            if(body.length()>40 && !image.isBlank() && !region.isBlank() && !signatures.add(body+"|"+image+"|"+region)) duplicateSuspected=true;
        }
        report.put("duplicateSuspected",duplicateSuspected);
        String quality=sorted.length<5?"INSUFFICIENT":sorted.length<10 || sellerUnknown>0 || sellerCounts.size()<2 || maxSellerShare>20 || duplicateSuspected || !flagged.isEmpty()?"LOW":"NORMAL";
        var qualityNode=report.putObject("quality"); qualityNode.put("level",quality);
        qualityNode.put("basis","게시글 단위 중복은 제거했으나 플랫폼 간 동일 실물 여부는 완전히 검증하지 못했습니다. 판매자 정보가 없으면 판매자 수와 편중을 확정하지 않습니다. 높은 등급은 추가 검증 전 부여하지 않습니다.");
        boolean costsKnown=facts.path("costs").path("total").isIntegralNumber();
        boolean candidateValid=facts.path("validPrice").asBoolean() && facts.path("conditionsComplete").asBoolean() && "on_sale".equals(facts.path("status").asText()) && facts.path("fresh24h").asBoolean();
        boolean nonSale=Set.of("BUY_REQUEST","EXCHANGE","RENTAL","SERVICE","GIVEAWAY").contains(facts.path("intent").asText());
        // Missing delivery fees limit total-cost conclusions, not an otherwise eligible asking-price comparison.
        String verdict=nonSale?"구매 후보 제외":!candidateValid || outlier.path("flagged").asBoolean() || duplicateSuspected?"추가 확인 필요":!"NORMAL".equals(quality)?"비교 근거 확인 필요":"상품가 기준 비교 제시가의 일반 범위";
        if(candidateValid && "NORMAL".equals(quality) && !outlier.path("flagged").asBoolean() && !duplicateSuspected) {
            double price=facts.path("askingPrice").asDouble(),median=comparison.path("median").asDouble();
            if(median>0) comparison.put("differencePercent",(median-price)/median*100);
            if(price<=comparison.path("q25").asDouble()) verdict="상품가 기준 상대적 저가: 조건부 검토";
            else if(price>comparison.path("q75").asDouble()) verdict="상품가 기준 비교 제시가 상단보다 높음";
        }
        report.put("finalJudgment",verdict); report.set("selectedFacts",facts);
        var reviewed=new ArrayList<Row>();
        if("NORMAL".equals(quality) && !duplicateSuspected) {
            var candidates=new ArrayList<>(comparable); if(candidateValid) candidates.add(new Row(selected,facts));
            for(var row:candidates) {
                var peers=new ArrayList<Long>(); for(var other:candidates) if(!identity(other.item).equals(identity(row.item))) peers.add(other.facts.path("askingPrice").asLong());
                if(row.facts.path("fresh6h").asBoolean() && row.facts.path("costs").path("total").isIntegralNumber()
                    && !UsedRobustStatistics.signal(mapper,row.facts.path("askingPrice").asLong(),peers).path("flagged").asBoolean()) reviewed.add(row);
            }
        }
        reviewed.sort(Comparator.<Row>comparingLong(row->row.facts.path("askingPrice").asLong()).thenComparing(row->identity(row.item)));
        counts.put("recommended",reviewed.size()); report.put("recommendedLowest",!reviewed.isEmpty());
        if(!reviewed.isEmpty()) {
            var low=reviewed.get(0); var record=source(mapper,low.item,low.facts.path("observedAt").asText());
            record.put("askingPrice",low.facts.path("askingPrice").asLong()); record.put("basis","수집 범위 내 가격·조건 검토 통과 최저 제시가"); report.set("lowestAskingCandidate",record);
            String method=facts.path("tradeMethod").asText("");
            if(!method.isBlank() && !method.equals("미확인")) reviewed.stream().filter(row->method.equals(row.facts.path("tradeMethod").asText()))
                .min(Comparator.comparingLong(row->row.facts.path("costs").path("total").asLong())).ifPresent(row->{
                    var total=source(mapper,row.item,row.facts.path("observedAt").asText()); total.put("total",row.facts.path("costs").path("total").asLong()); total.put("method",method); report.set("lowestTotalCandidate",total);
                });
        } else report.putNull("lowestAskingCandidate");
        report.put("lowestCandidateMessage",reviewed.isEmpty()?"현재 확보 자료에서 가격·조건·최신성 검토를 모두 통과한 추천 후보는 없습니다. 미확인 사항을 먼저 안내합니다.":"관측 범위에서 명시 조건과 가격·상태·비용을 검토한 후보입니다. 판매자 주장과 실제 물건·최종 거래 조건은 다시 확인해야 합니다.");
        var sources=mapper.createArrayNode(); sources.add(source(mapper,selected,time));
        comparable.stream().limit(4).forEach(row->sources.add(source(mapper,row.item,row.facts.path("observedAt").asText())));
        report.set("sources",sources); report.put("analysisAt",Instant.now().toString());
        report.put("scope","저장된 검색 근거와 이미 조회한 상세만 사용합니다. 14·30·90일이나 지역 범위를 새로 수집해 확장하지 않았습니다.");
        report.set("sectionOrder",mapper.valueToTree(List.of(1,2,3,4,5,6,7)));
        report.set("policyThresholds",mapper.valueToTree(Map.of("medianMinCount",5,"comparisonMinCount",10,"highCount",30,"highSellers",15,"recentDays",7,"recentShare",60,"sellerShare",20,"candidateHours",6,"statisticsHours",24,"modifiedZ",3.5)));
        var checks=mapper.createArrayNode();
        var missing=new ArrayList<String>();
        for(var entry:Map.of("model","모델","options","핵심 옵션","condition","상태","contents","구성품","unit","판매 단위","saleType","판매 유형").entrySet())
            if(!facts.path("fields").path(entry.getKey()).path("confirmed").asBoolean()) missing.add(entry.getValue());
        Collections.sort(missing);
        if(!missing.isEmpty()) checks.add("동일 조건의 정밀 비교를 위해 아직 확인되지 않은 "+String.join("·",missing)+" 정보를 확인해 주세요.");
        if(!facts.path("validPrice").asBoolean() || outlier.path("flagged").asBoolean()) checks.add("표시 금액이 목표 상품 전체의 현재 판매 희망가인지 확인해 주세요.");
        if(!"on_sale".equals(facts.path("status").asText()) || !facts.path("fresh6h").asBoolean()) checks.add("판매자에게 현재 판매 상태와 가격을 다시 확인해 주세요.");
        if(!costsKnown) checks.add("필수 배송비와 구매자 부담 수수료를 확인해 주세요. 미확인 비용은 0원으로 계산하지 않았습니다.");
        if(duplicateSuspected) checks.add("비슷한 본문·사진·지역의 반복 등록이 같은 실물인지 확인해 주세요.");
        report.set("checks",checks);
        var result=mapper.createObjectNode(); result.put("mode","detail"); result.put("policyVersion",VERSION);
        result.put("decision",candidateValid?"ADDITIONAL_CHECK":"CHECK_AVAILABILITY");
        result.put("headline",verdict+" · 확인된 매물 정보"); result.put("candidateCount",unique.size()); result.put("sameTitleCount",conditions);
        result.put("comparisonBasis",comparison.path("basis").asText()); result.put("referenceCount",prices.size());
        if(comparison.has("median")) for(String field:List.of("median","low","high")) result.set(field,comparison.path(field));
        if(facts.path("validPrice").asBoolean() && facts.path("askingPrice").isIntegralNumber()) result.set("currentPrice",facts.path("askingPrice"));
        result.set("detailReport",report); result.set("checks",checks); result.set("questions",checks);
        result.put("priceEvaluation",comparison.has("median")?"명시 조건이 일치한 관측 제시가의 참고 중앙값과 중간 50% 범위를 제시했습니다. 표본 품질과 비용·상태 미확인 여부를 함께 확인하세요.":"확보한 상세의 가격·조건과 미확인 항목을 먼저 정리했습니다. 일치 조건이 확인되지 않은 검색 가격을 같은 상품의 시세로 합치지 않았습니다.");
        result.set("limitations",mapper.valueToTree(List.of("게시된 판매 희망가 기준이며 최종 결제 금액이나 체결가가 아닙니다.","사양·상태·구성품은 판매자 기재이며 진위·성능·안전을 검증한 것이 아닙니다.","모델·용량·상태·구성품·판매 단위·판매 유형은 원문에서 명확하게 확인한 항목만 비교합니다. 정보 미제공은 정상·무료·판매 중으로 대체하지 않습니다.","통계 신호는 초기 검토 기준이며 사기 확률이 아닙니다. 신호만으로 유효 가격을 삭제하지 않았습니다.")));
        result.put("answer",verdict+". "+result.path("priceEvaluation").asText());
        return result;
    }
    private static String identity(JsonNode item) {return item.path("provider").asText()+":"+item.path("productId").asText();}
    private static ObjectNode source(ObjectMapper mapper,JsonNode item,String time) {
        var result=mapper.createObjectNode(); result.put("provider",item.path("provider").asText()); result.put("productId",item.path("productId").asText());
        result.put("title",UsedListingFacts.clean(item.path("title").asText())); result.put("observedAt",time);
        result.put("region",UsedListingFacts.clean(item.path("regionName").asText("미확인")));
        String body=UsedListingFacts.clean(item.path("description").asText("")); result.put("quote",body.substring(0,Math.min(160,body.length())));
        try {
            var uri=URI.create(item.path("url").asText()); String host=Objects.toString(uri.getHost(),"");
            boolean allowed="karrot".equals(item.path("provider").asText())?host.equals("daangn.com") || host.endsWith(".daangn.com"):host.equals("joongna.com") || host.endsWith(".joongna.com");
            if("https".equals(uri.getScheme()) && uri.getUserInfo()==null && allowed) result.put("url",new URI("https",null,host,-1,uri.getPath(),null,null).toString());
        } catch(Exception ignored) { /* No invented source URL. */ }
        return result;
    }
}
