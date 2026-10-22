package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeControllerTest {
    private final SessionVerifier sessions = mock(SessionVerifier.class);
    private final WishlistStore store = mock(WishlistStore.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private MockMvc mvc;
    @BeforeEach void setUp() throws Exception {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 필요"));
        when(sessions.verify("Bearer alice")).thenReturn(new SessionVerifier.User("alice", "alice@example.test"));
        when(sessions.verify("Bearer bob")).thenReturn(new SessionVerifier.User("bob", "bob@example.test"));
        when(catalog.products()).thenReturn(List.of(new ObjectMapper().readTree("{\"id\":\"p\"}")));
        mvc = MockMvcBuilders.standaloneSetup(new MeController(sessions, store, catalog))
                .setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
    }
    @Test void anonymousCannotReadOrWriteWishlist() throws Exception {
        mvc.perform(get("/api/v1/me/wishlist")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("$.error.code").value("HTTP_401"));
        mvc.perform(put("/api/v1/me/wishlist/p")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me/wishlist/p")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
    @Test void clientSuppliedUidCannotChangeOwner() throws Exception {
        mvc.perform(put("/api/v1/me/wishlist/p").header("Authorization", "Bearer alice").param("uid", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        verify(store).save("alice", "p"); verify(store, never()).save(eq("bob"), anyString());
        mvc.perform(delete("/api/v1/me/wishlist/p").header("Authorization", "Bearer bob").param("uid", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(false));
        verify(store).delete("bob", "p");
    }
    @Test void listingIsScopedToVerifiedUserAndNotCacheable() throws Exception {
        when(store.list("alice", "", 20)).thenReturn(new WishlistStore.Page(List.of(new WishlistStore.Entry("p", "2026-10-22T03:00:00Z")), null));
        when(store.list("bob", "", 20)).thenReturn(new WishlistStore.Page(List.of(), null));
        mvc.perform(get("/api/v1/me/wishlist").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].productId").value("p"));
        mvc.perform(get("/api/v1/me/wishlist").header("Authorization", "Bearer bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
    @Test void invalidCursorAndMissingProductDoNotReachTheStore() throws Exception {
        mvc.perform(get("/api/v1/me/wishlist").header("Authorization", "Bearer alice").param("cursor", "../bob"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/wishlist/missing").header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(store);
    }
}
