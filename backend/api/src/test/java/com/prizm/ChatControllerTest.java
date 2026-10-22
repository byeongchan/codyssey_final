package com.prizm;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    final SessionVerifier sessions = mock(SessionVerifier.class);
    final ChatStore store = mock(ChatStore.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        when(sessions.verify("")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(sessions.verify("Bearer alice")).thenReturn(new SessionVerifier.User("alice", "alice@example.test"));
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(sessions, store)).setControllerAdvice(new ApiErrors())
            .addFilters(new RequestIdFilter()).build();
    }
    @Test void anonymousCannotReadSendOrDelete() throws Exception {
        mvc.perform(get("/api/v1/me/conversations/p")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/conversations/p/messages").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me/conversations/p")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
    @Test void verifiedOwnerIsUsedForEveryOperation() throws Exception {
        when(store.get("alice", "p")).thenReturn(new ChatStore.Conversation("p", List.of()));
        when(store.send("alice", "p", "request-1234567890", "가격은?"))
            .thenReturn(new ChatStore.Conversation("p", List.of(new ChatStore.Turn("request-1234567890", "가격은?", "더미 답변", "2026-10-22T03:00:00Z", "2026-10-22T03:00:00Z"))));
        mvc.perform(get("/api/v1/me/conversations/p").header("Authorization", "Bearer alice").param("uid", "bob"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/me/conversations/p/messages").header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\" 가격은? \",\"requestId\":\"request-1234567890\",\"uid\":\"bob\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.turns[0].answer").value("더미 답변"));
        mvc.perform(delete("/api/v1/me/conversations/p").header("Authorization", "Bearer alice").param("uid", "bob"))
            .andExpect(status().isOk());
        verify(store).get("alice", "p"); verify(store).send("alice", "p", "request-1234567890", "가격은?"); verify(store).delete("alice", "p");
    }
    @Test void malformedOrOversizedQuestionsNeverReachStore() throws Exception {
        for (String body : List.of("{}", "{\"message\":\" \",\"requestId\":\"request-1234567890\"}",
                "{\"message\":5,\"requestId\":\"request-1234567890\"}", "{\"message\":\"hi\",\"requestId\":\"short\"}",
                "{\"message\":\"" + "a".repeat(1001) + "\",\"requestId\":\"request-1234567890\"}"))
            mvc.perform(post("/api/v1/me/conversations/p/messages").header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
