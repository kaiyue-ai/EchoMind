package com.echomind.console.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从 Authorization header 解析当前用户；缺失时回退 default 用户。 */
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final AuthTokenService tokenService;
    private final AuthApplicationService authService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/auth/login".equals(path) || "/api/auth/register".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        AuthUser user = resolveUser(request);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return;
        }
        try {
            AuthContext.set(user);
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private AuthUser resolveUser(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return resolveToken(header.substring("Bearer ".length()).trim());
        }
        String queryToken = request.getRequestURI().startsWith("/api/chat/stream/")
            ? request.getParameter("token")
            : null;
        if (queryToken == null || queryToken.isBlank()) {
            return AuthUser.DEFAULT;
        }
        return resolveToken(queryToken.trim());
    }

    private AuthUser resolveToken(String token) {
        return tokenService.verify(token)
            .map(authService::requireActiveUser)
            .orElse(null);
    }
}
