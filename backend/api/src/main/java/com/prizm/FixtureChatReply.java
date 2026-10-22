package com.prizm;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Pure, bounded fixture answers: no model invocation or side effects inside Firestore transactions. */
@Service
public class FixtureChatReply {
    private final CatalogRepository catalog;
    private final PriceObservations observations;
    public FixtureChatReply(CatalogRepository catalog, PriceObservations observations) { this.catalog = catalog; this.observations = observations; }
    public String referenceTime() { return catalog.referenceTime(); }
    public String answer(String id, String question, List<ChatStore.Turn> previous) {
        return CatalogViews.read(catalog, () -> answerPinned(id, question, previous));
    }
    private String answerPinned(String id, String question, List<ChatStore.Turn> previous) {
        var product = catalog.products().stream().filter(p -> id.equals(p.path("id").asText())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다"));
        var reference = Instant.parse(catalog.referenceTime());
        var day = reference.atZone(DataPolicy.SEOUL).toLocalDate();
        var history = catalog.prices(id).stream().filter(p -> {
            var date = LocalDate.parse(p.path("date").asText());
            return !date.isBefore(day.minusDays(29)) && !date.isAfter(day);
        }).sorted(Comparator.comparing(p -> p.path("date").asText())).toList();
        var current = observations.current(id);
        double average = history.stream().mapToLong(p -> p.path("comparablePrice").asLong()).average().orElse(0);
        boolean staleHistory = history.isEmpty() || DataPolicy.historyStale(history.get(history.size() - 1).path("date").asText(), reference);
        String basis = "[더미 답변 · Gemini 호출 없음] " + product.path("title").asText() + " 기준입니다. ";
        String topic = question;
        if (question.length() <= 15 && question.matches("(?s).*(그럼|그건|왜|더 자세|그 가격).*")
                && !question.matches("(?s).*(리뷰|후기|평점|알림|목표가|쿠폰|배송|최저가|가격|구매|살까|타이밍|기다|지금|평균|추천).*"))
            topic += previous.stream().skip(Math.max(0, previous.size() - 2)).map(ChatStore.Turn::question).reduce("", (a, b) -> a + " " + b);
        if (topic.matches("(?s).*(리뷰|후기|평점).*")) return basis + "실제 리뷰 수집이 연결되지 않아 실사용자 평가를 제공할 수 없습니다. 상품 상세에서 합성 리뷰 예시와 지원 상태를 확인하세요.";
        if (topic.matches("(?s).*(알림|목표가).*")) return basis + "상품 상세의 목표 가격 알림에서 원하는 금액을 설정하세요. 채팅에서는 알림 설정을 변경하지 않습니다.";
        if (topic.matches("(?s).*(쿠폰|배송|최저가|가격|구매|살까|타이밍|기다|지금|평균|추천|왜).*")) {
            if (!current.available() || staleHistory || history.size() < 7 || average <= 0)
                return basis + (current.available() ? String.format(Locale.KOREA, "유효한 확정 비교가는 %,d원입니다. ", current.price()) : "현재 유효한 확정 비교가가 없습니다. ")
                    + "유효한 가격 근거 또는 최근 7일 이상의 이력이 부족해 구매 판단을 보류합니다. 관측 이력은 최근 30일 범위 " + history.size() + "일입니다. 품절·배송비 미확인·만료 가격은 현재가에서 제외합니다.";
            double ratio = current.price() / average;
            String verdict = ratio <= .98 ? "평균보다 2% 이상 낮아 구매를 고려할 구간입니다." : ratio >= 1.02 ? "평균보다 2% 이상 높아 기다림을 고려할 구간입니다." : "평균 수준으로 중립적인 구간입니다.";
            return basis + String.format(Locale.KOREA, "확정 비교가 %,d원, 최근 %d일 평균 %,.0f원입니다. %s 배송비와 무조건 할인만 반영하며 조건부 쿠폰은 제외합니다. 미래 최저가나 가격 하락을 보장하지 않습니다.", current.price(), history.size(), average, verdict);
        }
        return basis + "이 상품의 현재 가격, 구매 시점, 배송비·쿠폰, 목표가 알림 또는 리뷰 지원 여부를 질문해 주세요. 다른 상품·외부 정보는 확인할 수 없습니다.";
    }
}
