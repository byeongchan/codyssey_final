package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertControllerTest {
    private final SessionVerifier sessions = mock(SessionVerifier.class);
    private final AlertStore store = mock(AlertStore.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PriceObservations prices = mock(PriceObservations.class);
    private MockMvc mvc;
    @BeforeEach void setUp() throws Exception {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 필요"));
        when(sessions.verify("Bearer alice")).thenReturn(new SessionVerifier.User("alice", null));
        when(catalog.products()).thenReturn(List.of(new ObjectMapper().readTree("{\"id\":\"p\"}")));
        when(store.get("alice", "p")).thenReturn(new AlertStore.Alert("p", "p", 400000, true, null, null, false, null));
        when(prices.current("p")).thenReturn(new PriceObservations.Observation(384900L, Instant.parse("2026-10-22T03:00:00Z")));
        mvc = MockMvcBuilders.standaloneSetup(new AlertController(sessions, store, catalog, prices))
                .setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
    }
    @Test void anonymousCannotReadNotificationsOrModifyAlerts() throws Exception {
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/alerts").contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"p\",\"targetPrice\":400000}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
    @Test void rejectsZeroFractionalAndStringTargetsWithoutWriting() throws Exception {
        for (String value : new String[]{"0", "-1", "1.5", "\"400000\"", "1000000000001", "null"}) {
            mvc.perform(post("/api/v1/me/alerts").header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productId\":\"p\",\"targetPrice\":" + value + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }
    @Test void createAndReadStateUseOnlyVerifiedUid() throws Exception {
        mvc.perform(post("/api/v1/me/alerts").header("Authorization", "Bearer alice").param("uid", "bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"p\",\"targetPrice\":400000,\"uid\":\"bob\"}"))
                .andExpect(status().isOk());
        verify(store).configure("alice", "p", 400000L, true, false);
        mvc.perform(patch("/api/v1/me/notifications/n1").header("Authorization", "Bearer alice").param("uid", "bob")
                .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        verify(store).markRead("alice", "n1");
    }
    @Test void patchLeavesUnspecifiedFieldsForTheAtomicMerge() throws Exception {
        mvc.perform(patch("/api/v1/me/alerts/p").header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(store).configure("alice", "p", null, false, true);
    }
}
