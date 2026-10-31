package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** Conservative observations of asking prices, never a used-market valuation. */
final class UsedAgentPolicy {
    static final String VERSION="used-agent-v2";
    static final List<String> LIMITATIONS=List.of(
        "조회한 일부 매물의 판매 희망 가격이며 실거래가·전체 중고 시세가 아닙니다.",
        "같은 제목이어도 모델·용량·상태·구성품이 같다고 확인된 것은 아닙니다.",
        "플랫폼별 매물 ID 중복은 제거하지만 다른 플랫폼의 동일 물건 재등록 여부는 확인하지 못합니다.",
        "설명은 판매자 주장입니다. 사진으로 상태를 판정하거나 사기 여부·미래 가격·협상 금액을 단정하지 않습니다.");
    static final List<String> QUESTIONS=List.of("현재도 거래 가능한가요?", "실제 사용 상태와 하자·수리 이력을 확인할 수 있나요?", "정확한 모델·용량과 포함되는 구성품은 무엇인가요?", "거래 장소·방식과 추가 비용이 있나요?");
    static boolean fresh(JsonNode data) {
        try { var time=Instant.parse(data.path("fetchedAt").asText()); return !time.isAfter(Instant.now().plusSeconds(30)) && time.isAfter(Instant.now().minusSeconds(86400)); }
        catch(Exception ex) { return false; }
    }
    static String title(JsonNode item) { return item.path("title").asText().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); }
    static ObjectNode assess(ObjectMapper mapper,JsonNode detail,List<JsonNode> candidates) {
        return UsedDetailAnalysis.assess(mapper,detail,candidates);
    }
}