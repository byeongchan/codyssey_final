package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Versioned explanation-only response. Numeric evidence and verdict stay server-owned. */
public final class AnalysisExplanation {
    public static final String VERSION = "explanation-v1";
    public record Result(List<String> reasons, List<String> risks, String model, String source) {}
    private static final Set<String> FIELDS = Set.of("productId", "ruleVersion", "explanationVersion", "evidenceSnapshotId", "generatedAt", "reasons", "risks", "model", "synthetic");
    private AnalysisExplanation() {}
    public static Result fallback(PurchaseDecision.Decision decision, String source) {
        return new Result(List.of(decision.reason()), List.of("서버의 더미 가격 규칙에 따른 결과입니다. 미래 가격 예측이 아닙니다."), "server-price-rules", source);
    }
    public static Result resolve(Map<String, Object> input, PurchaseDecision.Decision decision, Supplier<JsonNode> fetch) {
        try {
            JsonNode result = fetch.get();
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            result.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw new IllegalArgumentException(); });
            for (String field : List.of("productId", "ruleVersion", "explanationVersion", "evidenceSnapshotId"))
                if (!result.path(field).isTextual() || !Objects.equals(input.get(field), result.path(field).asText())) throw new IllegalArgumentException();
            if (!DataPolicy.instant(result.path("generatedAt"), "generatedAt").equals(Instant.parse((String)input.get("referenceTime")))
                || !result.path("synthetic").isBoolean() || !result.path("synthetic").asBoolean()
                || !Objects.equals(input.getOrDefault("explanationModel", "fixture-no-gemini-call"), result.path("model").asText())) throw new IllegalArgumentException();
            String provider = Objects.toString(input.getOrDefault("explanationProvider", "fixture"));
            if (!Set.of("fixture", "gemini").contains(provider)) throw new IllegalArgumentException();
            return new Result(texts(result.path("reasons")), texts(result.path("risks")), result.path("model").asText(),
                "gemini".equals(provider) ? "GEMINI_EXPLANATION" : "FIXTURE_EXPLANATION");
        } catch (RuntimeException ex) { return fallback(decision, "SERVER_FALLBACK"); }
    }
    private static List<String> texts(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 5) throw new IllegalArgumentException();
        var result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 500
                || value.asText().matches("(?s).*\\p{N}.*") || value.asText().matches("(?s).*(보장|확실히|할인 예정|재고 있음).*")) throw new IllegalArgumentException();
            result.add(value.asText());
        }
        return List.copyOf(result);
    }
}
