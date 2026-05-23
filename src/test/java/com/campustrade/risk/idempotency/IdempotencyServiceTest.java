package com.campustrade.risk.idempotency;

import com.campustrade.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyServiceTest {

    @Test
    void shouldRejectDuplicateAppointmentSubmitWithSameHeaderKey() {
        IdempotencyService service = new IdempotencyService(new InMemoryIdempotencyStore(), 300);

        assertDoesNotThrow(() -> service.guardAppointmentSubmit(1L, 2L, "request-1", "same-body"));
        assertThrows(BizException.class, () -> service.guardAppointmentSubmit(1L, 2L, "request-1", "same-body"));
    }

    @Test
    void shouldRejectDuplicateAppointmentSubmitByFingerprintWhenHeaderMissing() {
        IdempotencyService service = new IdempotencyService(new InMemoryIdempotencyStore(), 300);

        assertDoesNotThrow(() -> service.guardAppointmentSubmit(1L, 2L, null, "same-body"));
        assertThrows(BizException.class, () -> service.guardAppointmentSubmit(1L, 2L, null, "same-body"));
    }
}
