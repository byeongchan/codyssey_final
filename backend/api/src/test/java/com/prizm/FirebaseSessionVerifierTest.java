package com.prizm;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseSessionVerifierTest {
    @Test void deletionRequiresRecentAuthenticationRatherThanJustFreshToken() throws Exception {
        var access = mock(FirebaseAccess.class); var auth = mock(FirebaseAuth.class); var token = mock(FirebaseToken.class);
        when(access.auth()).thenReturn(auth); when(auth.verifyIdToken("token", true)).thenReturn(token);
        when(token.getUid()).thenReturn("alice");
        var verifier = new FirebaseSessionVerifier(access);
        long now = java.time.Instant.now().getEpochSecond();
        when(token.getClaims()).thenReturn(java.util.Map.of("auth_time", now - 600));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> verifier.verifyRecent("Bearer token")).getStatusCode().value());
        when(token.getClaims()).thenReturn(java.util.Map.of("auth_time", now));
        assertEquals("alice", verifier.verifyRecent("Bearer token").uid());
        when(token.getClaims()).thenReturn(java.util.Map.of("auth_time", now + 120));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> verifier.verifyRecent("Bearer token")).getStatusCode().value());
    }
    @Test void missingBearerDoesNotInitializeFirebase() {
        var access = mock(FirebaseAccess.class);
        var verifier = new FirebaseSessionVerifier(access);
        for (String header : new String[]{"", "Basic abc", "Bearer "}) {
            var error = assertThrows(ResponseStatusException.class, () -> verifier.verify(header));
            assertEquals(401, error.getStatusCode().value());
        }
        verifyNoInteractions(access);
    }
    @Test void usesVerifiedUidAndChecksRevocation() throws Exception {
        var access = mock(FirebaseAccess.class); var auth = mock(FirebaseAuth.class); var token = mock(FirebaseToken.class);
        when(access.auth()).thenReturn(auth); when(auth.verifyIdToken("token", true)).thenReturn(token);
        when(token.getUid()).thenReturn("verified-user"); when(token.getEmail()).thenReturn("user@example.test");
        assertEquals("verified-user", new FirebaseSessionVerifier(access).verify("Bearer token").uid());
        verify(auth).verifyIdToken("token", true);
    }
    @Test void rejectsExpiredAndRevokedTokensWithoutExposingSdkDetails() throws Exception {
        for (AuthErrorCode code : new AuthErrorCode[]{AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED}) {
            var access = mock(FirebaseAccess.class); var auth = mock(FirebaseAuth.class); var failure = mock(FirebaseAuthException.class);
            when(access.auth()).thenReturn(auth); when(failure.getAuthErrorCode()).thenReturn(code);
            when(auth.verifyIdToken("bad", true)).thenThrow(failure);
            var error = assertThrows(ResponseStatusException.class, () -> new FirebaseSessionVerifier(access).verify("Bearer bad"));
            assertEquals(401, error.getStatusCode().value());
        }
    }
    @Test void unsupportedModeOrRealProjectCannotOpenCloudConnections() {
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> new FirebaseAccess("cloud", "real-project").auth()).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> new FirebaseAccess("emulator", "real-project").firestore()).getStatusCode().value());
    }
}
