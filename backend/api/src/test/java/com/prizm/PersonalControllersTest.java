package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalControllersTest {
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final HistoryStore history = mock(HistoryStore.class);
    final CatalogRepository catalog = mock(CatalogRepository.class);
    final AccountDeletion deletion = mock(AccountDeletion.class);
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        var user = new SessionVerifier.User("alice", "alice@example.test");
        when(sessions.verify("Bearer alice")).thenReturn(user);
        when(sessions.verifyRecent("Bearer alice")).thenReturn(user);
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(sessions.verifyRecent("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(catalog.products()).thenReturn(List.of(new ObjectMapper().readTree("{\"id\":\"p\"}")));
        mvc = MockMvcBuilders.standaloneSetup(new HistoryController(sessions, history, catalog), new AccountDeletionController(sessions, deletion))
            .setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
    }
    @Test void anonymousCannotReadRecordOrDelete() throws Exception {
        mvc.perform(get("/api/v1/me/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/history/p")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me/account").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(history, deletion);
    }
    @Test void historyUsesVerifiedOwnerAndValidatesProduct() throws Exception {
        mvc.perform(post("/api/v1/me/history/p").header("Authorization", "Bearer alice").param("uid", "bob"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(history).record("alice", "p");
        mvc.perform(post("/api/v1/me/history/missing").header("Authorization", "Bearer alice")).andExpect(status().isNotFound());
        verify(history, never()).record(anyString(), eq("missing"));
        when(history.list("alice", "", 20)).thenReturn(new HistoryStore.Page(List.of(), null));
        mvc.perform(get("/api/v1/me/history").header("Authorization", "Bearer alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/me/history").header("Authorization", "Bearer alice").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }
    @Test void deletionRequiresExplicitBooleanConfirmationAndRecentSession() throws Exception {
        for (String body : List.of("{}", "{\"confirm\":false}", "{\"confirm\":\"true\"}"))
            mvc.perform(delete("/api/v1/me/account").header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        when(sessions.verifyRecent("Bearer old")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "재인증 필요"));
        mvc.perform(delete("/api/v1/me/account").header("Authorization", "Bearer old").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(deletion);
    }
    @Test void deletionCanBeResumedWithoutClientUidOverride() throws Exception {
        when(deletion.advance("alice")).thenReturn("DELETING", "DELETED");
        for (String state : List.of("DELETING", "DELETED"))
            mvc.perform(delete("/api/v1/me/account").header("Authorization", "Bearer alice").param("uid", "bob")
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value(state));
        verify(deletion, times(2)).advance("alice");
    }
}
