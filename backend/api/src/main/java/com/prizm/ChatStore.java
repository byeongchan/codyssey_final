package com.prizm;

import java.util.List;

public interface ChatStore {
    record Turn(String requestId, String question, String answer, String createdAt, String referenceTime) {}
    record Conversation(String productId, List<Turn> turns) {}
    Conversation get(String uid, String productId);
    Conversation send(String uid, String productId, String requestId, String message);
    void delete(String uid, String productId);
}
