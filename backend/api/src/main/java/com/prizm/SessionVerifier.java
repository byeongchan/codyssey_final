package com.prizm;

public interface SessionVerifier {
    record User(String uid, String email) {}
    User verify(String authorization);
    User verifyRecent(String authorization);
}
