package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.cloud.firestore.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Durable source-period history. Never expands a week/month into artificial daily observations. */
@Service
public class PeriodPriceHistory {
  private final FirebaseAccess firebase;
  private final ObjectMapper mapper;

  public PeriodPriceHistory(FirebaseAccess firebase, ObjectMapper mapper) {
    this.firebase = firebase;
    this.mapper = mapper;
  }

  public static ObjectNode normalized(ObjectMapper mapper, JsonNode history, String id) {
    if (!id.matches("[0-9]{1,30}")
        || !id.equals(history.path("productId").asText())
        || !"KRW".equals(history.path("currency").asText()))
      throw new IllegalArgumentException("Price history identity mismatch");
    var output = mapper.createObjectNode();
    output.put("productId", id);
    output.put("currency", "KRW");
    String basis = history.path("priceBasis").asText("UNKNOWN");
    if (!Set.of(
            "UNKNOWN",
            "CARD_WITH_SHIPPING",
            "CARD_WITHOUT_SHIPPING",
            "PERIOD_MINIMUM_UNKNOWN_SHIPPING")
        .contains(basis)) throw new IllegalArgumentException("Price basis");
    output.put("priceBasis", basis);
    for (String period : List.of("daily", "weekly", "monthly")) {
      var points = history.path(period);
      var dates = new TreeMap<String, JsonNode>();
      int max =
          switch (period) {
            case "daily" -> 366;
            case "weekly" -> 52;
            default -> 60;
          };
      if (!points.isMissingNode() && (!points.isArray() || points.size() > max))
        throw new IllegalArgumentException("Price history limit");
      for (var point : points) {
        String date = point.path("date").asText();
        if ("monthly".equals(period)) {
          if (!date.matches("[0-9]{4}-[0-9]{2}"))
            throw new IllegalArgumentException("Month format");
          YearMonth.parse(date);
        } else {
          if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Date format");
          LocalDate.parse(date);
        }
        var price = point.path("lowestPrice");
        if (!price.isNull()
            && (!price.isIntegralNumber()
                || !price.canConvertToLong()
                || price.asLong() < 1
                || price.asLong() > DataPolicy.MAX_AMOUNT))
          throw new IllegalArgumentException("Price format");
        if (dates.put(date, point) != null) throw new IllegalArgumentException("Duplicate period");
      }
      var array = output.putArray(period);
      dates.forEach(
          (date, point) -> {
            var row = array.addObject();
            row.put("date", date);
            row.set("lowestPrice", point.path("lowestPrice"));
          });
    }
    return output;
  }

  static void write(Transaction tx, Firestore db, ObjectMapper mapper, JsonNode data, long now)
      throws Exception {
    String source = data.path("source").asText(), id = data.path("productId").asText();
    if (!"reef-danawa".equals(source)
        || !data.path("synthetic").isBoolean()
        || data.path("synthetic").asBoolean())
      throw new IllegalArgumentException("Unsupported source");
    var history = normalized(mapper, data.path("priceHistory"), id);
    var root = db.collection("livePriceHistories").document(source + "_" + id);
    String json = mapper.writeValueAsString(history);
    var patch = new LinkedHashMap<String, Object>();
    patch.put("source", source);
    patch.put("productId", id);
    patch.put("currency", "KRW");
    patch.put("synthetic", false);
    patch.put("historyJson", json);
    patch.put(
        "version", FixtureSnapshots.hash(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    patch.put("fetchedAt", data.path("fetchedAt").textValue());
    patch.put("receivedAt", data.path("receivedAt").asText());
    patch.put("storedAt", now);
    tx.set(root, patch);
    for (String period : List.of("daily", "weekly", "monthly"))
      for (var point : history.path(period)) {
        var row = new LinkedHashMap<String, Object>();
        row.put("source", source);
        row.put("productId", id);
        row.put("granularity", period);
        row.put("date", point.path("date").asText());
        row.put(
            "lowestPrice",
            point.path("lowestPrice").isNull() ? null : point.path("lowestPrice").longValue());
        row.put("priceBasis", history.path("priceBasis").asText());
        row.put("fetchedAt", patch.get("fetchedAt"));
        row.put("receivedAt", patch.get("receivedAt"));
        row.put("storedAt", now);
        tx.set(root.collection(period).document(point.path("date").asText()), row);
      }
  }

  public JsonNode read(String id) {
    DanawaWishlist.validId(id);
    var doc =
        PersonalData.await(
            firebase
                .firestore()
                .collection("livePriceHistories")
                .document("reef-danawa_" + id)
                .get(),
            "기간별 가격 이력 읽기");
    if (!doc.exists()) return mapper.createObjectNode();
    try {
      if (!"reef-danawa".equals(doc.getString("source"))
          || !id.equals(doc.getString("productId"))
          || !Boolean.FALSE.equals(doc.getBoolean("synthetic")))
        throw new IllegalArgumentException("Stored identity");
      var data = mapper.createObjectNode();
      data.put("source", "reef-danawa");
      data.put("productId", id);
      data.put("synthetic", false);
      data.set(
          "priceHistory", normalized(mapper, mapper.readTree(doc.getString("historyJson")), id));
      data.put("fetchedAt", doc.getString("fetchedAt"));
      data.put("receivedAt", doc.getString("receivedAt"));
      data.put("historyVersion", doc.getString("version"));
      return data;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 기간별 가격 이력 형식을 확인하세요");
    }
  }
}
