package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsedProductGroupsTest {
  final ObjectMapper mapper = new ObjectMapper();

  JsonNode row(String id, String title, long price) {
    return mapper
        .createObjectNode()
        .put("provider", "joongna")
        .put("productId", id)
        .put("title", title)
        .put("price", price)
        .put("status", "on_sale");
  }

  @Test
  void capacityIsCombinedButProAndProMaxRemainSeparate() {
    var a = row("a", "아이폰15 128GB 블랙", 400000);
    var b = row("b", "iPhone 15 256GB 화이트", 600000);
    assertEquals(UsedProductGroups.identify(a), UsedProductGroups.identify(b));
    assertNotEquals(
        UsedProductGroups.identify(a),
        UsedProductGroups.identify(row("c", "아이폰 15 프로 256GB", 700000)));
    assertNotEquals(
        UsedProductGroups.identify(row("c", "아이폰 15 프로", 700000)),
        UsedProductGroups.identify(row("d", "iPhone 15 Pro Max", 800000)));
    var result = UsedProductGroups.assess(mapper, List.of(a, b));
    var group = result.path("groups").get(0);
    assertEquals(1, result.path("groupCount").asInt());
    assertEquals(500000, group.path("average").asLong());
    assertEquals(400000, group.path("low").asLong());
    assertEquals(600000, group.path("high").asLong());
    assertTrue(group.path("smallSample").asBoolean());
  }

  @Test
  void modelFreeFurnitureUsesProductKindsAndAccessoriesDoNotBecomePhones() {
    assertEquals("책상", UsedProductGroups.identify(row("a", "원목 책상", 50000)).name());
    assertEquals("CATEGORY", UsedProductGroups.identify(row("b", "사무용 의자", 30000)).kind());
    assertEquals("휴대폰 케이스", UsedProductGroups.identify(row("c", "아이폰15 케이스", 5000)).name());
    assertNull(UsedProductGroups.identify(row("d", "아이폰15 아이폰16 모두 판매", 500000)));
    assertNull(UsedProductGroups.identify(row("e", "좋은 물건 팔아요", 50000)));
  }

  @Test
  void duplicateIdsAndSelectedListingDoNotInflatePeerStatistics() {
    var a = row("a", "아이폰15 128GB", 400000);
    var b = row("b", "아이폰15 256GB", 600000);
    var result = UsedProductGroups.selected(mapper, a, List.of(a, a, b, b));
    assertEquals(1, result.path("count").asInt());
    assertEquals(600000, result.path("average").asLong());
    assertTrue(result.path("selectedExcluded").asBoolean());
  }

  @Test
  void invalidPricesCompletedListingsAndClearlyDifferentSaleTypesAreNotNormalPriceReferences() {
    var completed =
        (com.fasterxml.jackson.databind.node.ObjectNode) row("sold", "아이폰15 128GB", 800000);
    completed.put("status", "sold");
    var result =
        UsedProductGroups.assess(
            mapper,
            List.of(
                row("a", "아이폰15 고장 부품용", 10000),
                row("b", "아이폰15", 0),
                completed,
                row("c", "아이폰15 256GB", 500000)));
    var group = result.path("groups").get(0);
    assertEquals(4, group.path("count").asInt());
    assertEquals(1, group.path("analyzedCount").asInt());
    assertEquals(3, group.path("excludedCount").asInt());
    assertEquals(500000, group.path("low").asLong());
  }

  @Test
  void manufacturerCodesAndCategoryFallbackRemainAvailable() {
    assertEquals(
        UsedProductGroups.identify(row("a", "소니 WH-1000XM5 판매", 100000)),
        UsedProductGroups.identify(row("b", "Sony wh-1000xm5", 120000)));
    var category = (com.fasterxml.jackson.databind.node.ObjectNode) row("c", "모델명 없는 생활용품", 10000);
    category.put("categoryName", "주방용품");
    assertEquals("주방용품", UsedProductGroups.identify(category).name());
  }
}
