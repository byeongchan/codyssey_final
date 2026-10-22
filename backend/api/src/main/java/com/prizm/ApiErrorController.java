package com.prizm;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서블릿 오류 디스패치도 Spring 기본 오류 JSON 대신 같은 계약을 사용한다. */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error") public ResponseEntity<?> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        return ApiErrors.error(status, ApiErrors.message(status), RequestIds.of(request), new HttpHeaders());
    }
}
