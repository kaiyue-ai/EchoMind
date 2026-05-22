package com.echomind.console.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 仅保护项目三管理端 API。 */
@Component
@Order(10)
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminTokenService tokenService;
    private final AdminAuthApplicationService authService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/admin/") && !path.startsWith("/api/observability/")) {
            return true;
        }
        return "/api/admin/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        AdminUser user = resolveUser(request);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Invalid or expired admin token\"}");
            return;
        }
        try {
            AdminContext.set(user);
            filterChain.doFilter(request, response);
        } finally {
            AdminContext.clear();
        }
    }

    private AdminUser resolveUser(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return tokenService.verify(header.substring("Bearer ".length()).trim())
            .map(authService::requireActiveAdmin)
            .orElse(null);
    }
}
