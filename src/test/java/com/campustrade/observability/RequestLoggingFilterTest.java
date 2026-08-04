package com.campustrade.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestLoggingFilterTest {

    @Test
    void shouldReuseSafeIncomingRequestIdAndClearMdc() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        RequestLoggingFilter filter = new RequestLoggingFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        request.addHeader("X-Request-Id", "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                requestIdInsideChain.set(MDC.get("requestId")));

        assertEquals("req-123", response.getHeader("X-Request-Id"));
        assertEquals("req-123", requestIdInsideChain.get());
        assertNull(MDC.get("requestId"));
    }

    @Test
    void shouldGenerateRequestIdWhenIncomingHeaderIsUnsafe() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        RequestLoggingFilter filter = new RequestLoggingFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        request.addHeader("X-Request-Id", "bad request id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        String generated = response.getHeader("X-Request-Id");
        assertNotEquals("bad request id", generated);
        assertEquals(generated, UUID.fromString(generated).toString());
        assertNull(MDC.get("requestId"));
    }
}
