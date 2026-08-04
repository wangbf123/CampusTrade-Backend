package com.campustrade.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ObservabilityProperties properties;

    public RequestLoggingFilter(ObservabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAt = System.nanoTime();
        Throwable failure = null;
        MDC.put("requestId", requestId);
        response.setHeader(properties.getRequestIdHeader(), requestId);

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            logSlowRequestIfNeeded(request, response, durationMs, requestId, failure);
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(properties.getRequestIdHeader());
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (SAFE_REQUEST_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private void logSlowRequestIfNeeded(
            HttpServletRequest request,
            HttpServletResponse response,
            long durationMs,
            String requestId,
            Throwable failure
    ) {
        if (!properties.isSlowRequestEnabled() || durationMs < properties.getSlowRequestThresholdMs()) {
            return;
        }
        if (failure == null) {
            log.warn(
                    "Slow request method={} uri={} status={} durationMs={} requestId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs,
                    requestId
            );
            return;
        }
        log.warn(
                "Slow request method={} uri={} status={} durationMs={} requestId={} error={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                requestId,
                failure.getClass().getSimpleName()
        );
    }
}
