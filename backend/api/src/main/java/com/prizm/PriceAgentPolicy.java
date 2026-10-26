package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** All statistics and routing are deterministic. Period minima are not fabricated daily samples. */
public final class PriceAgentPolicy {
    public static final String VERSION = "price-agent-v12";
    public static final String OFF_TOPIC = "저는 상품의 가격 변동, 할인 정보, 판매처 비교와 구매 시점을 분석하는 AI 에이전트입니다. 현재 선택한 상품에 대해 질문해 주시면 가격 데이터를 바탕으로 분석해 드리겠습니다.";
    public record Quote(LocalDate date, long price) {}
    private PriceAgentPolicy() {}
    public static String intent(String question, String previous) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (q.matches(".*(프롬프트|시스템명령|지시를무시|ignoreprevious|비밀번호|api키|주식|코인|날씨|레시피|요리|정치|대통령|수학문제|코드작성|코드짜|파이썬코드|자바코드|소설|번역).*")) return "OFF_TOPIC";
        var groups = new LinkedHashMap<String, String>();
        groups.put("NEWS", "뉴스|기사|행사|시즌|계절|블랙프라이데이|출시");
        groups.put("SELLERS", "판매처|배송|쇼핑몰|현금|카드|쿠폰");
        groups.put("SPECS", "어때|뭐하는|사용처|제품설명|소개해줘|게임|작업|영상|편집|코딩용|업무|휴대|소음|발열|전력|내구|비교|성능|사양|스펙|용량|배터리|호환|무게|크기|특징|용도|장점|단점|차이|사용|브랜드|제조사|무슨제품|어떤제품|무슨상품|어떤상품|이게뭐|이건뭐");
        groups.put("TIMING", "구매|사도|살까|사야|살만|기다|추천|타이밍|언제|지금사|지금살|예산|만원|급해|필요해");
        groups.put("PRICE", "가격|최저|중앙값|평균|변동|할인|저렴|비싸|얼마|가성비|오르|내리|떨어|상승|하락|수급|공급|원가|관세");
        var found = groups.entrySet().stream().filter(e -> Pattern.compile(e.getValue()).matcher(q).find()).map(Map.Entry::getKey).toList();
        if (found.size() == 1) return found.get(0);
        if (found.size() > 1) {
            if (found.contains("SELLERS") && !found.contains("TIMING") && !found.contains("NEWS")) return "SELLERS";
            return "OVERVIEW";
        }
        if(q.matches("^(안녕|안녕하세요|고마워|감사합니다|반가워)[?？.!]*$")) return "CHAT";
        if(q.matches("^(응|네|좋아|다시|계속|그다음)[?？.!]*$")) return previous==null?"CLARIFY":previous;
        if(q.matches("^설명해줘[?？.!]*$")) return previous==null?"SPECS":previous;
        if (q.matches("^(그럼|그러면|왜|왜요|이유는|더자세히|자세히|조금더|장단점은|그건왜|위험은|좀더설명해줘|설명해줘|구체적으로|무슨뜻|그게무슨뜻)[?？.!]*$"))
            return previous == null ? "CLARIFY" : previous;
        if(q.matches("^(그게|그건|그럼|그러면|아까|방금|그중|좀더|구체적으로|왜|이유).*")) return previous==null?"CLARIFY":previous;
        if(q.matches("^(이거|이제품|선택한제품|이상품).*(설명|소개|뭐).*")) return "SPECS";
        return q.matches(".*(상품|이거|그거|이제품|이모델|이걸|이것|이건|이게|어떤|무슨).*") ? (previous==null?"CHAT":previous) : "OFF_TOPIC";
    }
    public static Map<String, Object> metrics(List<Quote> input, Long current, LocalDate today) {
        var unique = new TreeMap<LocalDate, Long>();
        for (var point : input) if (point.price() > 0 && !point.date().isAfter(today) && !point.date().isBefore(today.minusYears(3))) unique.merge(point.date(), point.price(), Math::min);
        var thirty = unique.entrySet().stream().filter(p -> !p.getKey().isBefore(today.minusDays(29))).toList();
        var ninety = unique.entrySet().stream().filter(p -> !p.getKey().isBefore(today.minusDays(89))).mapToLong(Map.Entry::getValue).sorted().toArray();
        Long low = thirty.stream().map(Map.Entry::getValue).min(Long::compareTo).orElse(null);
        Double median = ninety.length == 0 ? null : ninety.length % 2 == 0 ? ninety[ninety.length / 2 - 1] / 2.0 + ninety[ninety.length / 2] / 2.0 : (double)ninety[ninety.length / 2];
        var returns = new ArrayList<Double>();
        for (int i = 1; i < thirty.size(); i++) if (thirty.get(i - 1).getKey().plusDays(1).equals(thirty.get(i).getKey()))
            returns.add((thirty.get(i).getValue() / (double)thirty.get(i - 1).getValue() - 1) * 100);
        Double volatility = null;
        if (returns.size() >= 2) {
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            volatility = Math.sqrt(returns.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / (returns.size() - 1));
        }
        var seasonal = new ArrayList<Map<String, Object>>();
        for (int year = today.getYear() - 3; year < today.getYear(); year++) {
            final int y = year;
            var values = unique.entrySet().stream().filter(p -> p.getKey().getYear() == y && p.getKey().getMonth() == today.getMonth()).mapToLong(Map.Entry::getValue).sorted().toArray();
            if (values.length >= 3) seasonal.add(Map.of("year", year, "month", today.getMonthValue(), "observedDays", values.length, "median", values.length % 2 == 0 ? values[values.length / 2 - 1] / 2.0 + values[values.length / 2] / 2.0 : (double)values[values.length / 2]));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("lowest30", low); result.put("median90", median); result.put("days30", thirty.size()); result.put("days90", ninety.length);
        result.put("gapFromLowPercent", current == null || low == null ? null : (current / (double)low - 1) * 100);
        result.put("volatility30Percent", volatility); result.put("consecutiveReturnCount", returns.size());
        // Discount means <=95% of the observed 90-day median, not a verified promotion.
        result.put("discountFrequencyPercent", median == null || ninety.length < 7 || thirty.isEmpty() ? null : thirty.stream().filter(p -> p.getValue() <= median * .95).count() * 100.0 / thirty.size());
        result.put("discountDefinition", "90일 관측 중앙값보다 5% 이상 낮은 일별 표시가 비율; 행사 확인 아님");
        result.put("sameMonthPastYears", seasonal.size() >= 2 ? seasonal : List.of());
        result.put("seasonalityAvailable", seasonal.size() >= 2);
        return result;
    }
    public static Map<String,Object> periodFallback(JsonNode history, LocalDate today, Long comparableCurrent) {
        var series=new LinkedHashMap<String,List<Map.Entry<String,Long>>>();
        for(String period:List.of("daily","weekly","monthly")) {
            var unique=new TreeMap<String,Long>();
            for(var point:history.path(period)) {
                var price=point.path("lowestPrice"); if(!price.isIntegralNumber() || !price.canConvertToLong() || price.asLong()<1 || price.asLong()>DataPolicy.MAX_AMOUNT) continue;
                String date=point.path("date").asText();
                try {
                    boolean future="monthly".equals(period)?YearMonth.parse(date).isAfter(YearMonth.from(today)):LocalDate.parse(date).isAfter(today);
                    if(!future) unique.merge(date,price.asLong(),Math::min);
                } catch(DateTimeException ex) { continue; }
            }
            series.put(period,new ArrayList<>(unique.entrySet()));
        }
        String selected="none";
        for(String period:List.of("daily","weekly","monthly")) if(series.get(period).size()>=("daily".equals(period)?7:2)) {selected=period;break;}
        if("none".equals(selected)) for(String period:List.of("daily","weekly","monthly")) if(!series.get(period).isEmpty()) {selected=period;break;}
        var points=series.getOrDefault(selected,List.of());
        var values=points.stream().mapToLong(Map.Entry::getValue).sorted().toArray();
        var result=new LinkedHashMap<String,Object>(); result.put("granularity",selected);result.put("sampleCount",points.size());
        result.put("priceBasis",history.path("priceBasis").asText("UNKNOWN"));result.put("fallback",true);
        result.put("coverageStart",points.isEmpty()?null:points.get(0).getKey()); result.put("coverageEnd",points.isEmpty()?null:points.get(points.size()-1).getKey());
        result.put("lowest",values.length==0?null:values[0]);result.put("median",values.length==0?null:values.length%2==0?values[values.length/2-1]/2.0+values[values.length/2]/2.0:(double)values[values.length/2]);
        result.put("latestPeriodLowest",points.isEmpty()?null:points.get(points.size()-1).getValue());
        result.put("periodChangePercent",points.size()<2?null:(points.get(points.size()-1).getValue()/(double)points.get(0).getValue()-1)*100);
        result.put("currentComparisonPrice",comparableCurrent);
        result.put("gapFromLowPercent",comparableCurrent==null || values.length==0?null:(comparableCurrent/(double)values[0]-1)*100);
        result.put("canCompareCurrent",comparableCurrent!=null && values.length>0);
        result.put("limitation","선택 주기의 반환 표본 전체 범위 통계입니다. 30일 일별 변동성·90일 일별 중앙값·일별 할인 빈도로 대체하지 않습니다.");
        result.put("availableCounts",Map.of("daily",series.get("daily").size(),"weekly",series.get("weekly").size(),"monthly",series.get("monthly").size()));
        return result;
    }
}
