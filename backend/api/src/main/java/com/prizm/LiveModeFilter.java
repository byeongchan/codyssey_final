package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

/** In live mode, obsolete demo endpoints cannot leak synthetic records into the real project. */
@Component
public class LiveModeFilter extends OncePerRequestFilter {
    private final boolean live;
    private final ObjectMapper mapper;
    public LiveModeFilter(@Value("${prizm.provider}") String provider, ObjectMapper mapper) {
        live = "reef-danawa".equals(provider); this.mapper = mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (live && path.startsWith("/api/") && !path.startsWith("/api/v1/danawa/") && !path.startsWith("/api/v1/me/danawa/") && !path.equals("/api/v1/me")
                && !path.equals("/api/v1/me/account") && !path.equals("/api/v1/health")) {
            response.setStatus(404); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Request-Id", RequestIds.of(request));
            mapper.writeValue(response.getWriter(), Map.of("error", Map.of("code", "DEMO_DISABLED", "message", "실제 연결 모드에서는 더미 기능을 제공하지 않습니다", "requestId", RequestIds.of(request), "retryable", false)));
            return;
        }
        chain.doFilter(request, response);
    }
}
