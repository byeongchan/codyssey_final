package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Source-backed attributes; contextual inferences never establish comparison conditions. */
final class UsedListingFacts {
  static String clean(String text) {
    return text.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[이메일 제외]")
        .replaceAll("(?<!\\d)(?:\\+82[- ]?)?0\\d{1,2}[- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)", "[연락처 제외]")
        .replaceAll("(?:계좌번호|계좌)\\s*(?:[:：]|는|은)?\\s*[가-힣A-Za-z ]{0,15}\\d[\\d -]{5,30}", "[계좌 제외]")
        .replaceAll("(?:계좌번호|계좌|상세주소|거래주소|주소)\\s*[:：]\\s*[^\\n]{1,100}", "[개인정보 제외]")
        .replaceAll(
            "(?<![가-힣A-Za-z0-9])(?!(?:[가-힣]*프로|가로|세로)\\s)[가-힣]{2,}(?:대로|로|길)\\s+\\d+(?:-\\d+)?(?:\\s+\\d+(?:동|호))?(?![A-Za-z0-9])",
            "[상세주소 제외]");
  }

  static boolean recent(String time, long seconds) {
    try {
      var instant = Instant.parse(time);
      return !instant.isAfter(Instant.now().plusSeconds(30))
          && instant.isAfter(Instant.now().minusSeconds(seconds));
    } catch (Exception ex) {
      return false;
    }
  }

  private static String label(String body, String names) {
    var match =
        Pattern.compile("(?m)^\\s*(?:[-•]\\s*)?(?:" + names + ")\\s*[:：]\\s*([^\\n;|]{1,100})")
            .matcher(body);
    return match.find() ? match.group(0).strip() : "";
  }

  private static String value(String quote) {
    return quote.isBlank()
        ? ""
        : quote
            .substring(Math.max(quote.indexOf(':'), quote.indexOf('：')) + 1)
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
  }

  private static Long amount(String value) {
    var match = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(만원|만\\s*원|원)").matcher(value);
    if (!match.find()) return null;
    if (match.start() > 0 && value.charAt(match.start() - 1) == '-') return null;
    try {
      return new BigDecimal(match.group(1).replace(",", ""))
          .multiply(match.group(2).startsWith("만") ? BigDecimal.valueOf(10000) : BigDecimal.ONE)
          .longValueExact();
    } catch (Exception ex) {
      return null;
    }
  }

  static ObjectNode read(ObjectMapper mapper, JsonNode item, String observedAt) {
    String title = clean(item.path("title").asText()),
        body = clean(item.path("description").asText(""));
    var result = mapper.createObjectNode();
    var reasons = mapper.createArrayNode();
    var fields = mapper.createObjectNode();
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("model", "모델명|모델|제품명");
    labels.put("options", "용량|핵심 옵션|옵션|사양");
    labels.put("condition", "상태|상품 상태|작동 상태");
    labels.put("contents", "구성품|포함 구성");
    labels.put("unit", "판매 단위|판매단위|판매 구성");
    labels.put("saleType", "판매 유형|판매유형");
    for (var entry : labels.entrySet()) {
      String quote = label(body, entry.getValue());
      var field = fields.putObject(entry.getKey());
      field.put("value", value(quote));
      field.put("quote", quote);
      field.put("basis", quote.isBlank() ? "미확인" : "판매자 기재");
      field.put("confirmed", !quote.isBlank());
      if (quote.isBlank()) {
        var extracted = item.path("extractedFields").path(entry.getKey());
        String source = extracted.path("source").asText(),
            evidence = extracted.path("quote").asText(),
            text = extracted.path("value").asText().strip();
        String original = "title".equals(source) ? title : "description".equals(source) ? body : "";
        String certainty = extracted.path("certainty").asText();
        if (!text.isBlank()
            && text.length() <= 150
            && !evidence.isBlank()
            && evidence.length() <= 500
            && original.contains(evidence)
            && Set.of("EXPLICIT", "INFERRED").contains(certainty)) {
          boolean confirmed =
              "EXPLICIT".equals(certainty)
                  && evidence
                      .toLowerCase(Locale.ROOT)
                      .replaceAll("\\s+", "")
                      .contains(text.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""));
          field.put("value", text);
          field.put("quote", evidence);
          field.put("source", source);
          field.put("confirmed", confirmed);
          field.put(
              "basis", confirmed ? ("title".equals(source) ? "제목 기재" : "본문 기재") : "문맥 추정 · 확인 필요");
        }
      }
    }
    String intent = "UNKNOWN";
    if (title.matches(".*(삽니다|구합니다|구매합니다|매입합니다).*")) intent = "BUY_REQUEST";
    else if (title.matches(".*(교환만|교환 원해).*")) intent = "EXCHANGE";
    else if (title.matches(".*(대여합니다|렌탈료|대여료|대여만).*")) intent = "RENTAL";
    else if (title.matches(".*(수리 서비스|수리해 드|설치 서비스).*")) intent = "SERVICE";
    else if (title.matches(".*무료\\s*나눔.*")
        || Pattern.compile("(?m)^\\s*(?:상품 전체|상품 자체|본 상품)?\\s*무료\\s*나눔").matcher(body).find())
      intent = "GIVEAWAY";
    else if ((title + "\n" + body).matches("(?s).*(판매합니다|팝니다|판매가\\s*[:：]|판매 가격\\s*[:：]).*")
        || "on_sale".equals(item.path("status").asText())) intent = "SALE";
    result.put("intent", intent);
    Long price =
        item.path("price").isIntegralNumber() && item.path("price").canConvertToLong()
            ? item.path("price").asLong()
            : null;
    if (price != null) result.put("listedPrice", price);
    else result.putNull("listedPrice");
    String currentQuote = label(body, "현재 판매가|현재 가격|최종 판매가");
    boolean explicitlyCurrent = !currentQuote.isBlank();
    if (currentQuote.isBlank()) currentQuote = label(body, "판매가|판매 가격");
    Long explicit = amount(currentQuote);
    boolean pending =
        Pattern.compile(
                "(?:가격|금액)[^\\n.]{0,18}(?:현장\\s*(?:협의|결정)|문의\\s*(?:후|바람)|미정)|현장[^\\n.]{0,12}가격[^\\n.]{0,8}(?:정함|결정)")
            .matcher(body)
            .find();
    boolean temporary =
        Pattern.compile(
                "(?:표시|등록)[^\\n.]{0,12}(?:가격|금액)[^\\n.]{0,12}(?:임시|아무|실제[^\\n.]{0,5}아님)|임시\\s*가격")
            .matcher(body)
            .find();
    boolean partial =
        Pattern.compile("(?:표시|등록)[^\\n.]{0,12}(?:가격|금액)[^\\n.]{0,12}(?:예약금|보증금|계약금|월납|배송비)")
                .matcher(body)
                .find()
            || title.matches(".*(예약금|보증금|월납|월 납입|대여료).*");
    boolean range =
        Pattern.compile("(?:가격|금액)\\s*[:：][^\\n]{0,35}(?:원부터|만원부터|옵션별)|옵션별\\s*가격|시작\\s*가격")
            .matcher(body)
            .find();
    boolean resolved =
        temporary
            && explicit != null
            && explicit > 0
            && fields.path("unit").path("confirmed").asBoolean()
            && !range
            && !partial
            && !pending;
    if (pending) reasons.add("실제 전체 가격을 문의·현장에서 정하는 문구가 있음");
    if (temporary && !resolved) reasons.add("임시 가격이라는 원문 근거가 있음");
    if (partial) reasons.add("표시 금액이 전체 상품가 아닌 부분 금액일 수 있음");
    if (range) reasons.add("옵션별·시작 가격으로 전체 대상 금액이 미확정");
    var statedAmounts = new HashSet<Long>();
    var statements =
        Pattern.compile("(?m)^\\s*(?:판매가|판매 가격)\\s*[:：]\\s*[^\\n]{1,80}").matcher(body);
    while (statements.find()) {
      Long stated = amount(statements.group());
      if (stated != null) statedAmounts.add(stated);
    }
    boolean conflict =
        (explicit != null && price != null && !explicit.equals(price) && !resolved)
            || !explicitlyCurrent && statedAmounts.size() > 1;
    if (conflict
        && currentQuote.matches("(?s).*(현재|최종).*")
        && fields.path("unit").path("confirmed").asBoolean()
        && !partial
        && !range
        && !pending) {
      resolved = true;
      conflict = false;
    }
    if (conflict) reasons.add("가격란과 명시된 판매가가 달라 현재 유효 금액 확인 필요");
    if (resolved) price = explicit;
    String meaning =
        !"SALE".equals(intent)
            ? "NOT_PAID_SALE"
            : pending
                ? "UNDETERMINED"
                : partial
                    ? "PARTIAL_AMOUNT"
                    : range
                        ? "STARTING_PRICE"
                        : conflict
                            ? "CONFLICT"
                            : temporary && !resolved
                                ? "TEMPORARY"
                                : price == null
                                        || price <= 0
                                        || !fields.path("unit").path("confirmed").asBoolean()
                                    ? "UNKNOWN"
                                    : Pattern.compile("네고\\s*가능|가격\\s*협상\\s*가능")
                                            .matcher(body)
                                            .find()
                                        ? "NEGOTIABLE"
                                        : "ASKING_PRICE";
    if ("UNKNOWN".equals(meaning) && price != null && price > 0)
      reasons.add("판매 단위와 전체 상품 금액의 대상이 명시되지 않음");
    result.put("priceMeaning", meaning);
    result.put("priceResolvedFromBody", resolved);
    result.put("priceQuote", currentQuote);
    if (price != null && price > 0) result.put("askingPrice", price);
    else result.putNull("askingPrice");
    String shippingQuote = label(body, "배송비|필수 배송비"), feeQuote = label(body, "수수료|필수 수수료");
    Long shipping = amount(shippingQuote), fee = amount(feeQuote);
    if (shipping == null && shippingQuote.matches(".*(무료|없음).*")) shipping = 0L;
    if (fee == null && feeQuote.matches(".*(무료|없음).*")) fee = 0L;
    var costs = result.putObject("costs");
    costs.put("basis", "상품 판매 희망가와 명시된 필수 비용만 사용");
    if (shipping != null && shipping >= 0) costs.put("shipping", shipping);
    else costs.putNull("shipping");
    if (fee != null && fee >= 0) costs.put("fee", fee);
    else costs.putNull("fee");
    costs.put("shippingQuote", shippingQuote);
    costs.put("feeQuote", feeQuote);
    if (Set.of("ASKING_PRICE", "NEGOTIABLE").contains(meaning)
        && price != null
        && price > 0
        && shipping != null
        && shipping >= 0
        && fee != null
        && fee >= 0) {
      try {
        costs.put("total", Math.addExact(price, Math.addExact(shipping, fee)));
      } catch (ArithmeticException ex) {
        costs.putNull("total");
      }
    } else costs.putNull("total");
    costs.putArray("unconfirmed");
    if (shipping == null) costs.withArray("unconfirmed").add("필수 배송비");
    if (fee == null) costs.withArray("unconfirmed").add("필수 수수료");
    if (Set.of("ASKING_PRICE", "NEGOTIABLE").contains(meaning) && price != null && price > 0) {
      try {
        costs.put(
            "minimumKnown",
            Math.addExact(
                price, Math.addExact(shipping == null ? 0 : shipping, fee == null ? 0 : fee)));
      } catch (ArithmeticException ex) {
        costs.putNull("minimumKnown");
      }
    }
    String method = label(body, "거래 방식|거래방식");
    result.put(
        "tradeMethod",
        method.isBlank() ? clean(item.path("deliveryOptions").asText("미확인")) : value(method));
    result.put("status", item.path("status").asText("unknown"));
    result.put("observedAt", observedAt);
    result.put("fresh24h", recent(observedAt, 86400));
    result.put("fresh6h", recent(observedAt, 21600));
    result.put("hasDetail", !body.isBlank());
    result.set("fields", fields);
    result.set("reasons", reasons);
    boolean complete = true;
    var signature = new ArrayList<String>();
    for (String field : labels.keySet()) {
      String v = fields.path(field).path("value").asText();
      if (!fields.path(field).path("confirmed").asBoolean()
          || v.isBlank()
          || Set.of("미확인", "미상", "모름", "확인 필요").contains(v)) complete = false;
      signature.add(v);
    }
    result.put("conditionsComplete", complete);
    result.put("conditionSignature", complete ? String.join("|", signature) : "");
    result.put(
        "validPrice",
        Set.of("ASKING_PRICE", "NEGOTIABLE").contains(meaning)
            && !partial
            && !range
            && !pending
            && !conflict);
    return result;
  }
}
