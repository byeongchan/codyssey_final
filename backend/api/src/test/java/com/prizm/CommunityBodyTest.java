package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommunityBodyTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private CommunityBody.Body parse(Object doc) throws Exception {
    return CommunityBody.parse(
        mapper.valueToTree(
            Map.of("format", "richtext-v1", "content", mapper.writeValueAsString(doc))));
  }

  @Test
  void nestedFormattingIsPreserved() throws Exception {
    var text = Map.of("type", "text", "text", "내용");
    var emphasis = Map.of("type", "em", "children", List.of(text));
    var bold = Map.of("type", "strong", "children", List.of(emphasis));
    var paragraph = Map.of("type", "p", "children", List.of(bold));
    var body = parse(Map.of("type", "doc", "children", List.of(paragraph)));
    assertEquals("richtext-v1", body.format());
    assertTrue(body.content().contains("strong"));
  }

  @Test
  void rawHtmlUnsafeLinksUnknownAttributesAndEmptyDocumentsAreRejected() {
    for (Object child :
        List.of(
            Map.of("type", "script", "children", List.of()),
            Map.of(
                "type",
                "a",
                "href",
                "javascript:alert(1)",
                "children",
                List.of(Map.of("type", "text", "text", "link"))),
            Map.of("type", "p", "onclick", "evil()", "children", List.of()),
            Map.of("type", "image", "id", "12345678-1234-1234-1234-123456789abc"),
            Map.of("type", "text", "text", " "))) {
      assertThrows(
          ResponseStatusException.class,
          () -> parse(Map.of("type", "doc", "children", List.of(child))));
    }
    assertThrows(
        ResponseStatusException.class, () -> parse(Map.of("type", "doc", "children", List.of())));
  }

  @Test
  void limitCountsTextInsteadOfMarkup() throws Exception {
    var text = Map.of("type", "text", "text", "가".repeat(10000));
    var bold = Map.of("type", "strong", "children", List.of(text));
    assertDoesNotThrow(() -> parse(Map.of("type", "doc", "children", List.of(bold))));
    var tooLong = Map.of("type", "text", "text", "가".repeat(10001));
    assertThrows(
        ResponseStatusException.class,
        () -> parse(Map.of("type", "doc", "children", List.of(tooLong))));
  }

  @Test
  void legacyFormatsContinueToWork() {
    assertEquals(
        "plain", CommunityBody.parse(mapper.valueToTree(Map.of("content", "기존 글"))).format());
    assertEquals(
        "markdown-v1",
        CommunityBody.parse(
                mapper.valueToTree(Map.of("format", "markdown-v1", "content", "**기존 서식**")))
            .format());
  }
}
