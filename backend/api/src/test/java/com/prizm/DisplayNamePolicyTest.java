package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DisplayNamePolicyTest {
  @Test
  void rejectsReservedAndDisguisedNames() {
    for (String name :
        new String[] {
          "관리자", "관리 자", "어드민123", "운영자님", "Ａｄｍｉｎ", "ad.min", "ADMIN_01", "moderator", "관리\u200B자"
        })
      assertThrows(ResponseStatusException.class, () -> DisplayNamePolicy.validate(name), name);
  }

  @Test
  void acceptsOrdinaryNamesAndNormalizesWidth() {
    assertEquals("가격탐험가", DisplayNamePolicy.validate(" 가격탐험가 "));
    assertEquals("user12", DisplayNamePolicy.validate("ｕｓｅｒ１２"));
  }

  @Test
  void exactSevenDayBoundaryAndSameNameAreHandled() {
    long changed = 1000L;
    assertThrows(
        ResponseStatusException.class,
        () ->
            DisplayNamePolicy.checkInterval(
                "이전이름", "새이름", changed, changed + DisplayNamePolicy.INTERVAL - 1));
    assertDoesNotThrow(
        () ->
            DisplayNamePolicy.checkInterval(
                "이전이름", "새이름", changed, changed + DisplayNamePolicy.INTERVAL));
    assertDoesNotThrow(() -> DisplayNamePolicy.checkInterval("같은이름", "같은이름", changed, changed + 1));
    assertDoesNotThrow(() -> DisplayNamePolicy.checkInterval(null, "첫등록", null, changed));
  }
}
