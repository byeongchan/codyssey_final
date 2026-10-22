package com.prizm;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    static String message(int status) {
        return switch (status) {
            case 400 -> "요청 형식을 확인하세요";
            case 401 -> "로그인이 필요합니다";
            case 403 -> "요청 권한이 없습니다";
            case 404 -> "요청한 API 또는 리소스가 없습니다";
            case 405 -> "지원하지 않는 HTTP 메서드입니다";
            case 406 -> "지원하지 않는 응답 형식입니다";
            case 415 -> "Content-Type을 확인하세요";
            case 429 -> "요청이 많습니다. 잠시 후 다시 시도하세요";
            default -> status >= 500 ? "서버 처리 중 오류가 발생했습니다" : "요청을 처리하지 못했습니다";
        };
    }
    static ResponseEntity<?> error(int status, String message, String requestId, HttpHeaders extraHeaders) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        response.headers(extraHeaders).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId).header("Cache-Control", "no-store");
        if (status == 429) response.header("Retry-After", "5");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(Map.of("error", Map.of("code", "HTTP_" + status,
                "message", message, "requestId", requestId, "retryable", status == 429 || status >= 500)));
    }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> known(ResponseStatusException ex) {
        return error(ex.getStatusCode().value(), ex.getReason() == null ? message(ex.getStatusCode().value()) : ex.getReason(), RequestIds.current(), ex.getHeaders());
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ex) { return error(400, message(400), RequestIds.current(), new HttpHeaders()); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception ex) {
        if (ex instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            return error(status, message(status), RequestIds.current(), framework.getHeaders());
        }
        return error(500, message(500), RequestIds.current(), new HttpHeaders());
    }
}
