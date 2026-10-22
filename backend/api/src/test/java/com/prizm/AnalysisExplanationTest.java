package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisExplanationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String, Object> input = Map.of("productId", "p", "ruleVersion", PurchaseDecision.VERSION,
        "explanationVersion", AnalysisExplanation.VERSION, "evidenceSnapshotId", "fixture:hash", "referenceTime", "2026-10-22T03:00:00Z");
    final PurchaseDecision.Decision decision = PurchaseDecision.decide(9800L, 10000, 30, false);
    ObjectNode response() {
        var data = mapper.valueToTree(input); var result = (ObjectNode)data;
        result.remove("referenceTime"); result.put("generatedAt", "2026-10-22T03:00:00Z");
        result.put("model", "fixture-no-gemini-call").put("synthetic", true);
        result.putArray("reasons").add("관측 평균보다 낮은 구간입니다."); result.putArray("risks").add("미래 가격 예측이 아닙니다.");
        return result;
    }
    @Test void acceptsOnlyMatchingExplanationEvidence() {
        assertEquals("FIXTURE_EXPLANATION", AnalysisExplanation.resolve(input, decision, this::response).source());
    }
    @Test void acceptsOnlyConfiguredGeminiModelAndMarksRealExplanation() {
        var geminiInput = new HashMap<String, Object>(input);
        geminiInput.put("explanationProvider", "gemini"); geminiInput.put("explanationModel", "gemini-test-model");
        assertEquals("GEMINI_EXPLANATION", AnalysisExplanation.resolve(geminiInput, decision,
            () -> response().put("model", "gemini-test-model")).source());
        assertEquals("SERVER_FALLBACK", AnalysisExplanation.resolve(geminiInput, decision, this::response).source());
        assertEquals("SERVER_FALLBACK", AnalysisExplanation.resolve(input, decision,
            () -> response().put("model", "gemini-test-model")).source());
    }
    @Test void rejectsVerdictOverrideMismatchedEvidenceNumbersAndPromises() {
        var wrongVerdict = response().put("verdict", "WAIT");
        var wrongEvidence = response().put("evidenceSnapshotId", "different");
        var wrongTime = response().put("generatedAt", "2026-10-23T03:00:00Z");
        var numbers = response(); numbers.putArray("reasons").add("내일 100원입니다.");
        var promise = response(); promise.putArray("reasons").add("최저가 보장입니다.");
        for (var value : List.of(wrongVerdict, wrongEvidence, wrongTime, numbers, promise)) {
            var result = AnalysisExplanation.resolve(input, decision, () -> value);
            assertEquals("SERVER_FALLBACK", result.source()); assertEquals(List.of(decision.reason()), result.reasons());
        }
    }
    @Test void unavailableProviderRetainsServerDecisionAndSafeExplanation() {
        var result = AnalysisExplanation.resolve(input, decision, () -> { throw new IllegalStateException("private-key"); });
        assertEquals("SERVER_FALLBACK", result.source()); assertFalse(result.toString().contains("private-key"));
    }
}
