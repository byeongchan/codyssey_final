package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * A safe view of the actual normalized detail API response, not an extracted six-field template.
 */
final class UsedApiEvidence {
  static String text(JsonNode value) {
    if (!value.isTextual()) return "";
    String result = value.asText().strip();
    return java.util.Set.of("null", "undefined", "n/a")
            .contains(result.toLowerCase(java.util.Locale.ROOT))
        ? ""
        : result;
  }

  private static final List<String> FIELDS =
      List.of(
          "productId",
          "provider",
          "title",
          "price",
          "status",
          "description",
          "regionName",
          "createdAt",
          "bumpedAt",
          "categoryName",
          "deliveryOptions",
          "sellerName",
          "mannerScore",
          "descriptionTruncated");

  static ObjectNode product(ObjectMapper mapper, JsonNode detail) {
    var result = mapper.createObjectNode();
    var original = detail.path("product");
    for (String field : FIELDS) {
      var value = original.path(field);
      if (value.isMissingNode()) continue;
      if (value.isTextual()) {
        String text = text(value);
        if (text.isBlank()) result.putNull(field);
        else result.put(field, UsedListingFacts.clean(text));
      } else if (value.isNull() || value.isNumber() || value.isBoolean())
        result.set(field, value.deepCopy());
    }
    if (original.path("images").isArray()) result.put("imageCount", original.path("images").size());
    return result;
  }

  static ObjectNode metadata(ObjectMapper mapper, JsonNode detail, JsonNode product) {
    var result = mapper.createObjectNode();
    result.put("basis", "server-stored-detail-api");
    for (String field : List.of("source", "provider", "fetchedAt", "receivedAt")) {
      var value = detail.path(field);
      if (!value.isMissingNode()) result.set(field, value.deepCopy());
    }
    var available = result.putArray("availableFields");
    var missing = result.putArray("missingFields");
    for (String field : FIELDS) {
      var value = product.path(field);
      if (value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank())
        missing.add(field);
      else available.add(field);
    }
    result.put("imageAnalysisAvailable", false);
    return result;
  }
}
