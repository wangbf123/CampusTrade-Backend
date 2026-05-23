package com.campustrade.auth.security;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (isPublicEndpoint(request.getMethod(), path)) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring("Bearer ".length());
        }
        CurrentUserContext.set(tokenService.resolve(token)
                .orElseThrow(() -> BizException.unauthorized("登录已过期或 token 不合法")));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentUserContext.clear();
    }

    private boolean isPublicEndpoint(String method, String path) {
        if (path.equals("/api/auth/register") || path.equals("/api/auth/login")) {
            return true;
        }
        return "GET".equalsIgnoreCase(method) && (path.equals("/api/items") || path.startsWith("/api/items/"));
    }
}
