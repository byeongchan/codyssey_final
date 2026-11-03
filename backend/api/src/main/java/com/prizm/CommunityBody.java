package com.prizm;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Semantic rich text only: no browser HTML, styles, event handlers or arbitrary image URLs. */
final class CommunityBody {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> CONTAINERS =
      Set.of(
          "p",
          "h2",
          "h3",
          "h4",
          "strong",
          "em",
          "del",
          "ul",
          "ol",
          "li",
          "blockquote",
          "pre",
          "code",
          "a");

  record Body(String content, String format) {}

  static Body parse(JsonNode request) {
    if (request.has("format") && !request.path("format").isTextual()) throw invalid();
    String format = request.path("format").asText("plain");
    if (!Set.of("plain", "markdown-v1", "richtext-v1").contains(format)) throw invalid();
    String content =
        CommunityController.text(request, "content", format.equals("richtext-v1") ? 100000 : 10000);
    if (!format.equals("richtext-v1")) return new Body(content, format);
    try {
      var document = MAPPER.readTree(content);
      var validator = new Validator();
      if (!document.path("type").asText().equals("doc")) throw invalid();
      validator.node(document, 0);
      if (!validator.nonempty) throw invalid();
      return new Body(content, format);
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw invalid();
    }
  }

  private static class Validator {
    int nodes = 0, characters = 0;
    boolean nonempty = false;

    void node(JsonNode node, int depth) {
      if (node == null
          || !node.isObject()
          || ++nodes > 2000
          || depth > 16
          || !node.path("type").isTextual()) throw invalid();
      String type = node.path("type").asText();
      Set<String> fields =
          type.equals("text")
              ? Set.of("type", "text")
              : type.equals("image")
                  ? Set.of("type", "id")
                  : Set.of("br", "hr").contains(type)
                      ? Set.of("type")
                      : type.equals("a")
                          ? Set.of("type", "href", "children")
                          : Set.of("type", "children");
      node.fieldNames()
          .forEachRemaining(
              key -> {
                if (!fields.contains(key)) throw invalid();
              });
      switch (type) {
        case "text" -> {
          if (!node.path("text").isTextual()) throw invalid();
          String value = node.path("text").asText();
          characters += value.length();
          if (characters > 10000) throw invalid();
          nonempty |= !value.isBlank();
          return;
        }
        case "br" -> {
          return;
        }
        case "hr" -> {
          nonempty = true;
          return;
        }
        case "image" -> {
          throw invalid();
        }
        case "doc" -> {
          if (depth != 0) throw invalid();
        }
        default -> {
          if (!CONTAINERS.contains(type)) throw invalid();
        }
      }
      if (type.equals("a")) {
        if (!node.path("href").isTextual() || node.path("href").asText().length() > 2048)
          throw invalid();
        try {
          var uri = URI.create(node.path("href").asText());
          if (!Set.of("http", "https")
                  .contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
              || uri.getHost() == null
              || uri.getUserInfo() != null) throw invalid();
        } catch (IllegalArgumentException ex) {
          throw invalid();
        }
      }
      if (!node.path("children").isArray()) throw invalid();
      for (var child : node.path("children")) node(child, depth + 1);
    }
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "본문 서식·내용 또는 10,000자 제한을 확인하세요");
  }
}
