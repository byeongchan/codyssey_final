package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Scope the conclusion to known API facts; keep unknowns separate from confirmed problems. */
final class UsedPurchaseJudgment {
  static void apply(ObjectMapper mapper, ObjectNode assessment, JsonNode product) {
    if (!assessment.path("detailReport").isObject()) return;
    var report = (ObjectNode) assessment.path("detailReport");
    var facts = report.path("selectedFacts");
    var group = assessment.path("selectedSearchGroup");
    var judgment = mapper.createObjectNode();
    var known = judgment.putArray("confirmedEvidence");
    var pending = judgment.putArray("reviewRequired");
    String title = UsedApiEvidence.text(product.path("title")),
        status = UsedApiEvidence.text(product.path("status"));
    boolean priceKnown =
        product.path("price").isIntegralNumber() && product.path("price").asLong() > 0;
    if (!title.isBlank()) known.add("상세 API 상품명: " + title);
    if (priceKnown)
      known.add(
          "상세 API 등록가격: "
              + java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA)
                  .format(product.path("price").asLong())
              + "원");
    if ("on_sale".equals(status)) known.add("조회 당시 API 판매 중 표시");
    if (!UsedApiEvidence.text(product.path("regionName")).isBlank())
      known.add("매물 표시 지역: " + UsedApiEvidence.text(product.path("regionName")));
    if (!UsedApiEvidence.text(product.path("deliveryOptions")).isBlank())
      known.add("API 거래 방식: " + UsedApiEvidence.text(product.path("deliveryOptions")));
    if (!UsedApiEvidence.text(product.path("description")).isBlank())
      known.add("상품 설명이 제공되어 판매자 기재 내용을 분석에 사용함");
    else pending.add("상품 설명이 제공되지 않아 하자·구성 등은 추가 검토가 필요합니다.");
    if (product.path("descriptionTruncated").asBoolean())
      pending.add("상품 설명이 일부만 전달되어 생략된 내용은 추가 검토가 필요합니다.");
    if (!facts.path("costs").path("total").isIntegralNumber())
      pending.add("배송비·수수료를 포함한 최종 총비용은 추가 검토가 필요합니다. 등록가격 판단과 구분합니다.");
    if (!Set.of("on_sale", "reserved", "sold").contains(status))
      pending.add("현재 판매 가능 여부는 API에서 확인되지 않아 추가 검토가 필요합니다.");
    if (!facts.path("fresh6h").asBoolean()) pending.add("최근 가격·판매 상태를 다시 확인할 필요가 있습니다.");
    boolean nonSale =
        Set.of("BUY_REQUEST", "EXCHANGE", "RENTAL", "SERVICE", "GIVEAWAY")
            .contains(facts.path("intent").asText());
    boolean priceProblem =
        Set.of("UNDETERMINED", "PARTIAL_AMOUNT", "STARTING_PRICE", "CONFLICT", "TEMPORARY")
            .contains(facts.path("priceMeaning").asText());
    String old = report.path("finalJudgment").asText(), summary, scope;
    boolean strict =
        facts.path("conditionsComplete").asBoolean()
            && facts.path("validPrice").asBoolean()
            && "on_sale".equals(status)
            && facts.path("fresh24h").asBoolean()
            && "NORMAL".equals(report.path("quality").path("level").asText())
            && !report.path("outlier").path("flagged").asBoolean()
            && !report.path("duplicateSuspected").asBoolean();
    if (nonSale) {
      summary = "확인된 거래 유형 기준 일반 유료 구매 비교 대상에서 제외";
      scope = "EXCLUDED";
    } else if (Set.of("reserved", "sold").contains(status)) {
      summary = "등록 정보는 분석할 수 있으나 현재 즉시 구매 가능한 매물로 판단하지 않음";
      scope = "UNAVAILABLE";
    } else if (priceProblem || !priceKnown) {
      summary = "확인된 상품 정보는 검토 가능하며 가격의 대상·유효성 판단은 보류";
      scope = "PRICE_REVIEW";
      pending.add("표시 금액의 의미·현재 유효 가격을 확인해야 가격 수준을 판단할 수 있습니다.");
    } else if (report.path("outlier").path("flagged").asBoolean()) {
      summary = "관측 가격 차이가 커 가격·상품 조건 검토를 우선할 매물";
      scope = "PRICE_REVIEW";
      pending.add("통계적 가격 차이 신호의 실제 원인은 추가 검토가 필요합니다. 사기로 확정한 것은 아닙니다.");
    } else if (group.path("analyzedCount").asInt() >= 5
        && group.path("centralLow").isNumber()
        && group.path("centralHigh").isNumber()) {
      long price = product.path("price").asLong();
      String position =
          price < group.path("centralLow").asDouble()
              ? "가격이 낮은 편"
              : price > group.path("centralHigh").asDouble() ? "가격이 높은 편" : "중간 가격대에 있어 대체로 무난한 수준";
      String basis = "MODEL".equals(group.path("kind").asText()) ? "같은 모델명" : "같은 상품 종류";
      summary = basis + " 검색 가격대와 비교하면 " + position + "입니다";
      scope = "SEARCH_GROUP_REFERENCE";
      known.add(basis + " 검색 참고군 " + group.path("analyzedCount").asInt() + "개와 중앙 50% 범위를 사용함");
      var reference = judgment.putObject("priceReference");
      reference.put("basis", basis);
      reference.put("name", group.path("name").asText());
      reference.put("count", group.path("analyzedCount").asInt());
      reference.set("listedPrice", product.path("price"));
      for (String field :
          java.util.List.of("low", "average", "high", "median", "centralLow", "centralHigh"))
        if (group.path(field).isNumber()) reference.set(field, group.path(field));
      reference.put("smallSample", false);
      pending.add("이 가격대는 옵션·상태·구성 차이가 포함된 검색 참고입니다. 같은 세부 조건의 적정 가격 여부는 추가 검토가 필요합니다.");
    } else if (strict) {
      summary = old;
      scope = "MATCHED_ASKING_PRICE";
      known.add("확인된 동일 조건·현재 판매 제시가 비교 결과를 사용함");
    } else if (group.path("analyzedCount").asInt() > 0
        && group.path("low").isNumber()
        && group.path("high").isNumber()) {
      String basis = "MODEL".equals(group.path("kind").asText()) ? "같은 모델명" : "같은 상품 종류";
      long price = product.path("price").asLong();
      String position =
          price < group.path("low").asDouble()
              ? "관측 가격보다 낮습니다"
              : price > group.path("high").asDouble() ? "관측 가격보다 높습니다" : "관측 가격 범위 안에 있습니다";
      summary = basis + "의 소수 검색 매물과 비교하면 " + position + ". 표본이 적어 적절한 가격인지 단정하기는 어렵습니다";
      scope = "SEARCH_GROUP_REFERENCE";
      var reference = judgment.putObject("priceReference");
      reference.put("basis", basis);
      reference.put("name", group.path("name").asText());
      reference.put("count", group.path("analyzedCount").asInt());
      reference.set("listedPrice", product.path("price"));
      reference.put("smallSample", true);
      for (String field : java.util.List.of("low", "average", "high", "median"))
        if (group.path(field).isNumber()) reference.set(field, group.path(field));
      pending.add("소수 표본이며 옵션·상태·구성이 달라 가격 적절성은 추가 검토가 필요합니다.");
    } else {
      summary = "등록가격과 상품 설명을 참고할 수 있으나 같은 모델·종류의 비교 가격이 없어 가격 적절성은 판단하기 어렵습니다";
      scope = "PRODUCT_INFORMATION";
      pending.add("동일 조건 가격 우위를 판단할 비교 자료는 추가 검토가 필요합니다. 상품 정보 자체가 무효라는 의미는 아닙니다.");
    }
    if (group.path("analyzedCount").asInt() > 0 && group.path("analyzedCount").asInt() < 5)
      known.add("같은 그룹의 소수 관측 가격은 참고할 수 있으나 대표 시세 판단에는 사용하지 않음");
    summary = summary.replaceAll("[.。]+$", "") + ". 구매 판단의 참고로 활용해 주세요.";
    judgment.put("summary", summary);
    judgment.put("scope", scope);
    judgment.put(
        "basis", "확인된 상세 API 정보와 서버 계산 자료 기준. 미확인은 별도 추가 검토 사항이며, 거래 안전·실물 성능·최종 총액의 보증이 아닙니다.");
    report.put("comparisonJudgment", old);
    report.put("finalJudgment", summary);
    report.set("purchaseJudgment", judgment);
    report.set("checks", pending.deepCopy());
    assessment.set("checks", pending.deepCopy());
    assessment.set("questions", pending.deepCopy());
    assessment.put("headline", summary);
  }
}
