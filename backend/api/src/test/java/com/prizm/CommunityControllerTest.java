package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommunityControllerTest {
  @Test
  void noticesAndDeletedPostsRejectComments() {
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class,
                () -> CommunityController.canComment("NOTICE", false))
            .getStatusCode()
            .value());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> CommunityController.canComment("GENERAL", true))
            .getStatusCode()
            .value());
    assertDoesNotThrow(() -> CommunityController.canComment("GENERAL", false));
  }

  @Test
  void onlyAuthorAndAdminCanDelete() {
    assertThrows(
        ResponseStatusException.class, () -> CommunityController.owner("other", "author", false));
    assertDoesNotThrow(() -> CommunityController.owner("author", "author", false));
    assertDoesNotThrow(() -> CommunityController.owner("admin", "author", true));
  }

  @Test
  void editingRequiresTheOriginalAuthor() {
    assertDoesNotThrow(() -> CommunityController.author("author", "author"));
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class, () -> CommunityController.author("other", "author"))
            .getStatusCode()
            .value());
    assertThrows(
        ResponseStatusException.class, () -> CommunityController.author("admin", "author"));
    assertThrows(ResponseStatusException.class, () -> CommunityController.author("user", ""));
  }

  @Test
  void rejectsBlankNonTextAndOversizedContent() throws Exception {
    var mapper = new ObjectMapper();
    for (String json :
        new String[] {
          "{}", "{\"content\":12}", "{\"content\":\"   \"}", "{\"content\":\"1234\"}"
        }) {
      var body = mapper.readTree(json);
      assertEquals(
          400,
          assertThrows(
                  ResponseStatusException.class, () -> CommunityController.text(body, "content", 3))
              .getStatusCode()
              .value());
    }
  }
}
