package com.prizm;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;

final class PersonalData {
    private PersonalData() {}
    static <T> T await(ApiFuture<T> future) {
        try { return future.get(8, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (TimeoutException ex) { future.cancel(true); throw unavailable(); }
        catch (ExecutionException ex) {
            for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause())
                if (cause instanceof ResponseStatusException status) throw status;
            throw unavailable();
        }
    }
    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "개인 데이터 처리에 실패했습니다. 다시 시도해 주세요");
    }
    static boolean deleting(Transaction transaction, DocumentReference root) throws Exception {
        return transaction.get(root).get().getString("deletionState") != null;
    }
    static void active(Transaction transaction, DocumentReference root) throws Exception {
        if (deleting(transaction, root)) throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다. 내 계정에서 삭제를 이어서 진행하세요");
    }
}
