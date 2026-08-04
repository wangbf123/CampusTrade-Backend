package com.campustrade.auth.security;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public AuthInterceptor(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
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
        AuthenticatedUser tokenUser = tokenService.resolve(token)
                .orElseThrow(() -> BizException.unauthorized("登录已过期或 token 不合法"));
        User user = userRepository.findById(tokenUser.id())
                .orElseThrow(() -> BizException.unauthorized("用户不存在，请重新登录"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw BizException.forbidden("账号已被封禁");
        }
        CurrentUserContext.set(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
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
