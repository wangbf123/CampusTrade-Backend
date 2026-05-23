package com.campustrade.risk.ratelimit;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.common.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }
        String identity = resolveIdentity(request, rateLimit.scope());
        long windowBucket = System.currentTimeMillis() / (rateLimit.windowSeconds() * 1000L);
        String key = "rate:" + rateLimit.key() + ":" + rateLimit.scope() + ":" + identity + ":" + windowBucket;
        boolean allowed = rateLimiter.tryAcquire(key, rateLimit.permits(), Duration.ofSeconds(rateLimit.windowSeconds()));
        if (!allowed) {
            throw BizException.tooManyRequests("请求过于频繁，请稍后再试");
        }
        return true;
    }

    private String resolveIdentity(HttpServletRequest request, RateLimitScope scope) {
        if (scope == RateLimitScope.USER) {
            AuthenticatedUser user = CurrentUserContext.require();
            return String.valueOf(user.id());
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
