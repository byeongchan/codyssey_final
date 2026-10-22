package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixtureChatReplyTest {
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final PriceObservations observations = mock(PriceObservations.class);
    final ObjectMapper mapper = new ObjectMapper();
    FixtureChatReply replies;
    @BeforeEach void setup() throws Exception {
        when(catalog.products()).thenReturn(List.of(mapper.readTree("{\"id\":\"p\",\"title\":\"상품 P\"}")));
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(9700L, Instant.parse("2026-10-22T03:00:00Z")));
        when(catalog.prices("p")).thenReturn(IntStream.range(0, 7).mapToObj(i -> (JsonNode) mapper.createObjectNode()
            .put("date", "2026-10-" + (22 - i)).put("comparablePrice", 10000)).toList());
        replies = new FixtureChatReply(catalog, observations);
    }
    @Test void sufficientFreshEvidenceSupportsOnlyFixtureRule() {
        var answer = replies.answer("p", "지금 구매할까?", List.of());
        assertTrue(answer.contains("9,700")); assertTrue(answer.contains("2% 이상 낮아")); assertTrue(answer.contains("Gemini 호출 없음"));
    }
    @Test void missingStaleOrInsufficientEvidenceCannotRecommendBuying() {
        when(catalog.referenceTime()).thenReturn("2026-10-24T03:00:00Z");
        assertTrue(replies.answer("p", "가격 추천", List.of()).contains("판단을 보류"));
        when(catalog.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(catalog.prices("p")).thenReturn(List.of(mapper.createObjectNode().put("date", "2026-10-22").put("comparablePrice", 10000)));
        assertTrue(replies.answer("p", "가격 추천", List.of()).contains("판단을 보류"));
        when(catalog.prices("p")).thenReturn(List.of());
        assertTrue(replies.answer("p", "가격 추천", List.of()).contains("판단을 보류"));
        when(observations.current("p")).thenReturn(new PriceObservations.Observation(null, null));
        assertTrue(replies.answer("p", "구매", List.of()).contains("판단을 보류"));
    }
    @Test void unsupportedReviewsAreNotInventedAndShortFollowupHasBoundedContext() {
        var previous = List.of(new ChatStore.Turn("id", "리뷰 알려줘", "", "", ""));
        assertTrue(replies.answer("p", "그럼 더 자세히", previous).contains("리뷰 수집이 연결되지 않아"));
        assertTrue(replies.answer("p", "그럼 가격은?", previous).contains("9,700"));
        assertTrue(replies.answer("p", "알림 설정", List.of()).contains("채팅에서는 알림 설정을 변경하지 않습니다"));
    }
}
