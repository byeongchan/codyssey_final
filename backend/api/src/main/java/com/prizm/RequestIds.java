package com.prizm;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;

final class RequestIds {
    static final String ATTRIBUTE = RequestIds.class.getName();
    private RequestIds() {}
    static String of(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id) return id;
        String id = UUID.randomUUID().toString(); request.setAttribute(ATTRIBUTE, id); return id;
    }
    static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            return of(attributes.getRequest());
        return UUID.randomUUID().toString();
    }
}
