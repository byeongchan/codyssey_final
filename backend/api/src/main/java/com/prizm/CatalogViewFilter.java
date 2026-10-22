package com.prizm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class CatalogViewFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Value("${prizm.provider:fixture}") private String provider = "fixture";
    private final SnapshotCatalogRepository catalog;
    public CatalogViewFilter(SnapshotCatalogRepository catalog) { this.catalog = catalog; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!"fixture".equals(provider)) { chain.doFilter(request, response); return; }
        try (var ignored = catalog.scope()) {
            if ("GET".equals(request.getMethod()) && !request.getRequestURI().startsWith("/api/v1/catalog-source")) {
                response.setHeader("X-Catalog-Version", catalog.dataVersion());
                response.setHeader("X-Catalog-Source", catalog.mode());
            }
            chain.doFilter(request, response);
        }
    }
}
