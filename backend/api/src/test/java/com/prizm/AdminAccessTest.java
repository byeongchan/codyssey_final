package com.prizm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.cloud.firestore.DocumentSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminAccessTest {
  @Test
  void permissionsAreReloadedAndOrdinaryMembersCannotGrantRoles() {
    var sessions = mock(SessionVerifier.class);
    var firebase = mock(FirebaseAccess.class);
    var db = mock(com.google.cloud.firestore.Firestore.class);
    var ref = mock(com.google.cloud.firestore.DocumentReference.class);
    var doc = mock(DocumentSnapshot.class);
    when(firebase.firestore()).thenReturn(db);
    when(db.document(AdminAccess.DOCUMENT)).thenReturn(ref);
    when(ref.get()).thenReturn(com.google.api.core.ApiFutures.immediateFuture(doc));
    when(sessions.verify("token"))
        .thenReturn(new SessionVerifier.User("owner", "owner@example.test"));
    when(doc.get("uids")).thenReturn(List.of("owner"));
    var service = new AdminAccess(sessions, firebase);
    assertEquals("owner", service.requireAdmin("token").uid());
    when(doc.get("uids")).thenReturn(List.of("other"));
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class, () -> service.change("token", "target", "ADMIN"))
            .getStatusCode()
            .value());
    verify(firebase, never()).auth();
    verify(db, never()).runTransaction(any());
  }

  @Test
  void firestoreDocumentIsAuthority() {
    var doc = mock(DocumentSnapshot.class);
    when(doc.get("uids")).thenReturn(List.of("owner"));
    assertEquals(Set.of("owner"), AdminAccess.members(doc));
    assertFalse(AdminAccess.members(doc).contains("admin"));
  }

  @Test
  void malformedConfigurationFailsClosed() {
    var doc = mock(DocumentSnapshot.class);
    when(doc.exists()).thenReturn(true);
    assertThrows(ResponseStatusException.class, () -> AdminAccess.members(doc));
    when(doc.get("uids")).thenReturn(List.of(123));
    assertThrows(ResponseStatusException.class, () -> AdminAccess.members(doc));
  }

  @Test
  void grantsRevocationsAndLastAdministrator() {
    assertEquals(
        Set.of("owner", "second"), AdminAccess.changed(Set.of("owner"), "second", "ADMIN"));
    assertEquals(
        Set.of("second"), AdminAccess.changed(Set.of("owner", "second"), "owner", "MEMBER"));
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> AdminAccess.changed(Set.of("owner"), "owner", "MEMBER"))
            .getStatusCode()
            .value());
    assertEquals(Set.of("owner"), AdminAccess.changed(Set.of("owner"), "owner", "ADMIN"));
  }
}
