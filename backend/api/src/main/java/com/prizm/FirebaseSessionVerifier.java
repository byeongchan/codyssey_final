package com.prizm;

import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

@Service
public class FirebaseSessionVerifier implements SessionVerifier {
    private final FirebaseAccess firebase;
    public FirebaseSessionVerifier(FirebaseAccess firebase) { this.firebase = firebase; }
    public User verify(String authorization) {
        return verify(authorization, false);
    }
    public User verifyRecent(String authorization) {
        return verify(authorization, true);
    }
    private User verify(String authorization, boolean recent) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.substring(7).isBlank() || authorization.length() > 16384)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
        try {
            var token = firebase.auth().verifyIdToken(authorization.substring(7).trim(), true);
            if (recent) {
                Object value = token.getClaims().get("auth_time");
                long now = java.time.Instant.now().getEpochSecond();
                if (!(value instanceof Number time) || time.longValue() < now - 300 || time.longValue() > now + 30)
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "계정 삭제 전 비밀번호로 다시 인증해 주세요");
            }
            String uid = token.getUid();
            if (uid == null || uid.isBlank() || uid.length() > 128 || uid.contains("/")
                    || uid.equals(".") || uid.equals("..") || uid.matches("__.*__"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "지원하지 않는 사용자 식별자입니다");
            return new User(uid, token.getEmail());
        } catch (FirebaseAuthException ex) {
            String code = ex.getAuthErrorCode() == null ? "" : ex.getAuthErrorCode().name();
            if (Set.of("INVALID_ID_TOKEN", "EXPIRED_ID_TOKEN", "REVOKED_ID_TOKEN", "USER_DISABLED", "USER_NOT_FOUND", "TENANT_ID_MISMATCH").contains(code))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "인증 서비스를 확인하고 다시 시도해 주세요");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 로그인 토큰입니다");
        }
    }
}
