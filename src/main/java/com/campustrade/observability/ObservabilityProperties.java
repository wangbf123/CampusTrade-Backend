package com.campustrade.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilityProperties {

    private String requestIdHeader = "X-Request-Id";
    private boolean slowRequestEnabled = true;
    private long slowRequestThresholdMs = 1000;

    public String getRequestIdHeader() {
        return requestIdHeader;
    }

    public void setRequestIdHeader(String requestIdHeader) {
        this.requestIdHeader = requestIdHeader == null || requestIdHeader.isBlank()
                ? "X-Request-Id"
                : requestIdHeader.trim();
    }

    public boolean isSlowRequestEnabled() {
        return slowRequestEnabled;
    }

    public void setSlowRequestEnabled(boolean slowRequestEnabled) {
        this.slowRequestEnabled = slowRequestEnabled;
    }

    public long getSlowRequestThresholdMs() {
        return slowRequestThresholdMs;
    }

    public void setSlowRequestThresholdMs(long slowRequestThresholdMs) {
        this.slowRequestThresholdMs = Math.max(1, slowRequestThresholdMs);
    }
}
