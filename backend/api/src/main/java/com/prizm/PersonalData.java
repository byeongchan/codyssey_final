package com.prizm;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;

final class PersonalData {
    private PersonalData() {}
    static <T> T await(ApiFuture<T> future) {
        return await(future, "개인 데이터 처리");
    }
    static <T> T await(ApiFuture<T> future, String operation) {
        try { return future.get(8, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw failure(ex, operation, "INTERRUPTED"); }
        catch (TimeoutException ex) { future.cancel(true); throw failure(ex, operation, "DEADLINE_EXCEEDED"); }
        catch (ExecutionException ex) {
            for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause())
                if (cause instanceof ResponseStatusException status) throw status;
            throw failure(ex, operation, "UNKNOWN");
        }
    }
    private static ResponseStatusException failure(Throwable error, String operation, String initialCode) {
        String code = initialCode; Throwable leaf = error;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            leaf = cause;
            if (cause instanceof com.google.api.gax.rpc.ApiException api) code = api.getStatusCode().getCode().name();
            if (cause instanceof io.grpc.StatusRuntimeException grpc) code = grpc.getStatus().getCode().name();
        }
        // Provider descriptions may contain project paths or credentials. Log only safe diagnostics.
        org.slf4j.LoggerFactory.getLogger(PersonalData.class).warn("Firestore operation={} status={} causeType={} requestId={}",
            operation, code, leaf.getClass().getName(), RequestIds.current());
        String detail = switch (code) {
            case "PERMISSION_DENIED" -> "Firestore 접근 권한이 없습니다. 서버 서비스 계정의 프로젝트·IAM 권한을 확인하세요";
            case "UNAUTHENTICATED" -> "Firestore 서버 인증에 실패했습니다. GOOGLE_APPLICATION_CREDENTIALS를 확인하세요";
            case "NOT_FOUND" -> "Firestore 프로젝트 ID와 기본 데이터베이스 생성을 확인하세요";
            case "FAILED_PRECONDITION" -> "Firestore 조회 조건을 충족하지 못했습니다. 인덱스 상태와 데이터베이스 설정을 확인하세요";
            case "INVALID_ARGUMENT" -> "Firestore 요청 조건 또는 저장 데이터 형식을 확인하세요";
            case "DEADLINE_EXCEEDED" -> "Firestore 응답 대기 시간이 초과되었습니다. 네트워크와 Firebase 연결을 확인하세요";
            case "UNAVAILABLE" -> "Firestore에 연결할 수 없습니다. 네트워크와 서비스 상태를 확인하세요";
            case "RESOURCE_EXHAUSTED" -> "Firestore 사용량 또는 할당량을 확인하세요";
            case "ABORTED" -> "동시 데이터 변경으로 처리가 중단되었습니다. 잠시 후 다시 시도하세요";
            case "INTERRUPTED" -> "저장소 요청이 중단되었습니다";
            default -> "저장소 처리에 실패했습니다. Spring 터미널의 Firestore 상태 로그를 확인하세요";
        };
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, operation + ": " + detail, error);
    }
    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "개인 데이터 처리에 실패했습니다. 다시 시도해 주세요");
    }
    static boolean deleting(Transaction transaction, DocumentReference root) throws Exception {
        var document = transaction.get(root).get();
        return document.exists() && document.getString("deletionState") != null;
    }
    static void active(Transaction transaction, DocumentReference root) throws Exception {
        if (deleting(transaction, root)) throw new ResponseStatusException(HttpStatus.CONFLICT, "계정 삭제가 진행 중입니다. 내 계정에서 삭제를 이어서 진행하세요");
    }
}
