package com.prizm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MemberProfileControllerTest {
  @Test
  void clientsCannotSetRolesOrChangeTimestamps() throws Exception {
    var sessions = mock(SessionVerifier.class);
    var firebase = mock(FirebaseAccess.class);
    when(sessions.verify("Bearer user"))
        .thenReturn(new SessionVerifier.User("user-1", "member@example.test"));
    var admins = mock(AdminAccess.class);
    when(admins.requireAdmin("Bearer user"))
        .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
    var controller = new MemberProfileController(sessions, firebase, admins);
    var mapper = new ObjectMapper();
    for (String body :
        new String[] {
          "{\"displayName\":\"일반회원\",\"role\":\"ADMIN\"}",
          "{\"displayName\":\"일반회원\",\"memberNameChangedAt\":0}",
          "{\"displayName\":\"관리자\"}"
        }) {
      var json = mapper.readTree(body);
      assertEquals(
          400,
          assertThrows(ResponseStatusException.class, () -> controller.update("Bearer user", json))
              .getStatusCode()
              .value());
    }
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, () -> controller.admin("Bearer user"))
            .getStatusCode()
            .value());
    verifyNoInteractions(firebase);
  }
}
