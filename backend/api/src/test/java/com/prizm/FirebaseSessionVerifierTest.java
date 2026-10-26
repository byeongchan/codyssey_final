package com.prizm;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseSessionVerifierTest {
    @Test void unverifiedEmailCannotUseMemberApis() throws Exception {
        var firebase=mock(FirebaseAccess.class); var auth=mock(FirebaseAuth.class); var token=mock(FirebaseToken.class);
        when(firebase.auth()).thenReturn(auth);
        when(auth.verifyIdToken("test-token",true)).thenReturn(token);
        when(token.getClaims()).thenReturn(Map.of("email_verified",false));
        var error=assertThrows(ResponseStatusException.class,()->new FirebaseSessionVerifier(firebase).verify("Bearer test-token"));
        assertEquals(403,error.getStatusCode().value());
    }
    @Test void verifiedEmailCanUseMemberApis() throws Exception {
        var firebase=mock(FirebaseAccess.class); var auth=mock(FirebaseAuth.class); var token=mock(FirebaseToken.class);
        when(firebase.auth()).thenReturn(auth);
        when(auth.verifyIdToken("test-token",true)).thenReturn(token);
        when(token.getClaims()).thenReturn(Map.of("email_verified",true));
        when(token.getUid()).thenReturn("user-1"); when(token.getEmail()).thenReturn("member@example.test");
        assertEquals("user-1",new FirebaseSessionVerifier(firebase).verify("Bearer test-token").uid());
    }
    @Test void unverifiedOwnerCanDeleteAccountOnlyWithRecentAuthentication() throws Exception {
        var firebase=mock(FirebaseAccess.class); var auth=mock(FirebaseAuth.class); var token=mock(FirebaseToken.class);
        when(firebase.auth()).thenReturn(auth);
        when(auth.verifyIdToken("test-token",true)).thenReturn(token);
        when(token.getUid()).thenReturn("user-1"); when(token.getEmail()).thenReturn("member@example.test");
        when(token.getClaims()).thenReturn(Map.of("email_verified",false,"auth_time",Instant.now().getEpochSecond()));
        var verifier=new FirebaseSessionVerifier(firebase);
        assertEquals("user-1",verifier.verifyRecent("Bearer test-token").uid());
        when(token.getClaims()).thenReturn(Map.of("email_verified",false,"auth_time",Instant.now().minusSeconds(600).getEpochSecond()));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->verifier.verifyRecent("Bearer test-token")).getStatusCode().value());
    }
}
