package com.prizm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class LiveModeTest {
  @Test
  void liveRepositoryDoesNotReadFixtureDirectory() throws Exception {
    var repo =
        new FixtureRepository(
            new ObjectMapper(), "missing-directory", "reef-danawa", "development", "");
    assertTrue(repo.products().isEmpty());
    assertTrue(repo.categories().isEmpty());
    assertEquals("live-no-fixtures", repo.dataVersion());
  }

  @Test
  void liveFilterBlocksDemoButAllowsRealRoutes() throws Exception {
    for (String path :
        new String[] {"/api/v1/products", "/api/v1/me/chat", "/api/v1/internal/collect"}) {
      var response = new MockHttpServletResponse();
      var reached = new AtomicBoolean();
      new LiveModeFilter("reef-danawa", new ObjectMapper())
          .doFilter(new MockHttpServletRequest("GET", path), response, (a, b) -> reached.set(true));
      assertFalse(reached.get());
      assertEquals(404, response.getStatus());
    }
    for (String path :
        new String[] {
          "/api/v1/me/danawa/search",
          "/api/v1/danawa/search",
          "/api/v1/danawa/product",
          "/api/v1/danawa/offers",
          "/api/v1/community/posts",
          "/api/v1/me/community/posts",
          "/api/v1/musinsa/search",
          "/api/v1/me/musinsa/wishlist"
        }) {
      var reached = new AtomicBoolean();
      new LiveModeFilter("reef-danawa", new ObjectMapper())
          .doFilter(
              new MockHttpServletRequest("POST", path),
              new MockHttpServletResponse(),
              (a, b) -> reached.set(true));
      assertTrue(reached.get());
    }
  }
}
