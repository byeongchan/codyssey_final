package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Distribution of the retrieved asking prices, not proof of fraud or fair market value. */
final class UsedSearchAnalysis {
    static ObjectNode assess(ObjectMapper mapper,List<JsonNode> input) { return assess(mapper,input,3); }
    static ObjectNode assess(ObjectMapper mapper,List<JsonNode> input,int minimum) {
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var row:input) unique.putIfAbsent(row.path("provider").asText()+":"+row.path("productId").asText(),row);
        var valid=new ArrayList<JsonNode>(); var excluded=mapper.createArrayNode();
        for(var row:unique.values()) {
            String status=row.path("status").asText(); var price=row.path("price");
            if(!price.isIntegralNumber() || !price.canConvertToLong() || price.asLong()<=0) exclude(mapper,excluded,row,"나눔·가격 미확인");
            else if(Set.of("sold","reserved").contains(status)) exclude(mapper,excluded,row,"예약·판매 완료");
            else valid.add(row);
        }
        long[] sorted=valid.stream().mapToLong(row->row.path("price").asLong()).sorted().toArray();
        // Only a clear low-price cluster: >=5 observations, >=3 higher-price references,
        // a >=50x gap, and a low cluster no larger than the reference group.
        long cutoff=0; double gap=0;
        if(sorted.length>=5) for(int i=0;i<sorted.length-1;i++) {
            double ratio=sorted[i+1]/(double)sorted[i];
            if(sorted[i]<=1000 && sorted[i+1]>=10000 && sorted.length-i-1>=3 && i+1<=sorted.length-i-1 && ratio>=50 && ratio>gap) {
                cutoff=sorted[i]; gap=ratio;
            }
        }
        final long lowCutoff=cutoff;
        var retained=new ArrayList<JsonNode>();
        for(var row:valid) {
            if(lowCutoff>0 && row.path("price").asLong()<=lowCutoff) exclude(mapper,excluded,row,"분포에서 크게 벗어난 저가 의심 가격");
            else retained.add(row);
        }
        long[] prices=retained.stream().mapToLong(row->row.path("price").asLong()).sorted().toArray();
        var result=mapper.createObjectNode(); result.put("mode","search"); result.put("policyVersion","used-search-agent-v2");
        result.set("regionSummary",UsedRegionAnalysis.assess(mapper,unique.values()));
        result.put("candidateCount",unique.size()); result.put("analyzedCount",prices.length); result.put("excludedCount",excluded.size());
        result.put("lowPriceExcludedCount",valid.size()-retained.size()); result.put("unknownStatusCount",retained.stream().filter(row->!"on_sale".equals(row.path("status").asText())).count());
        result.put("lowPriceCutoff",lowCutoff); result.put("lowPriceGapRatio",gap);
        result.put("decision","INSUFFICIENT_DATA"); result.put("headline",prices.length>=3?"검색 매물 가격 분포":minimum==1?"확인된 가격 참고":"분석 표본 부족");
        result.put("comparisonBasis","현재 표시 조건의 조회 매물 · 판매 희망 가격 · 비정상 저가 후보 제외");
        var distribution=mapper.createArrayNode();
        if(prices.length>=minimum) {
            result.put("low",prices[0]); result.put("high",prices[prices.length-1]);
            result.put("average",Math.round(Arrays.stream(prices).asDoubleStream().average().orElse(0)));
            result.put("median",quantile(prices,.5)); result.put("centralLow",quantile(prices,.25)); result.put("centralHigh",quantile(prices,.75));
            var bins=new TreeMap<Long,long[]>();
            for(long price:prices) {
                long power=1; while(power<=price/10) power*=10;
                long start=price>=power*5?power*5:price>=power*2?power*2:power;
                long end=start==power?power*2:start==power*2?power*5:power*10;
                long[] bin=bins.computeIfAbsent(start,k->new long[]{end,0}); bin[1]++;
            }
            long max=bins.values().stream().mapToLong(bin->bin[1]).max().orElse(0);
            for(var entry:bins.entrySet()) {
                var bin=distribution.addObject(); bin.put("from",entry.getKey()); bin.put("to",entry.getValue()[0]);
                bin.put("count",entry.getValue()[1]); bin.put("share",Math.round(entry.getValue()[1]*1000.0/prices.length)/10.0); bin.put("dominant",entry.getValue()[1]==max);
            }
            result.put("answer","이상 저가 후보 등을 제외한 "+prices.length+"개 매물의 희망 가격은 최저 "+prices[0]+"원, 평균 "+result.path("average").asLong()+"원, 최고 "+prices[prices.length-1]+"원입니다. 빈도가 높은 가격 구간과 중앙 50% 범위를 함께 확인하세요. 모델·상태·구성품 차이 때문에 적정 구매가로 단정할 수 없습니다.");
        } else result.put("answer","분석 가능한 매물이 3개 미만입니다. 가격 분포나 적정 가격을 판단하기에는 자료가 부족합니다.");
        result.set("distribution",distribution);
        var excludedPreview=mapper.createArrayNode();
        for(int i=0;i<Math.min(40,excluded.size());i++) excludedPreview.add(excluded.get(i));
        result.set("excludedListings",excludedPreview);
        result.set("sellerNotes",mapper.createArrayNode());
        result.set("questions",mapper.valueToTree(List.of("같은 모델·용량·구성품인가요?", "희망 가격이 전체 상품 가격인가요, 예약금·개별 부품 가격인가요?", "실제 하자와 거래 가능 여부를 확인할 수 있나요?")));
        result.set("limitations",mapper.valueToTree(List.of(
            "검색 결과에는 모델·용량·상태·구성품과 액세서리가 섞일 수 있습니다. 전체 중고 시세나 실거래가가 아닙니다.",
            "저가 제외는 가격 분포에 따른 의심 후보 분류이며 허위매물·사기로 확인한 것이 아닙니다. 실제 저렴한 매물이 제외될 수 있습니다.",
            "가격이 1,000원 이하인 군과 10,000원 이상인 군 사이가 50배 이상 벌어지고 충분한 표본이 있을 때만 저가 군을 제외합니다. 표본이 부족하거나 의심 가격이 다수면 자동 판별을 보류합니다.",
            "판매 상태 미제공 매물은 미확인 상태로 포함하며, 플랫폼 간 동일 물건 재등록 여부는 확인하지 못합니다.")));
        return result;
    }
    private static double quantile(long[] values,double q) {
        double index=(values.length-1)*q; int lower=(int)index; int upper=(int)Math.ceil(index);
        return values[lower]+(values[upper]-values[lower])*(index-lower);
    }
    private static void exclude(ObjectMapper mapper,ArrayNode target,JsonNode item,String reason) {
        var row=mapper.createObjectNode();
        for(String field:List.of("provider","productId","title","price")) row.set(field,item.path(field));
        row.put("reason",reason); target.add(row);
    }
}
