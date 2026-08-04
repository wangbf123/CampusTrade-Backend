package com.campustrade.registration.service;

import com.campustrade.admin.repository.InMemoryAdminOperationLogRepository;
import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.common.exception.BizException;
import com.campustrade.registration.RegistrationProperties;
import com.campustrade.registration.dto.CreateInviteCodeRequest;
import com.campustrade.registration.repository.InMemoryInviteCodeRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InviteCodeServiceTest {

    @Test
    void shouldRejectBlankCodeWhenInviteRequired() {
        InviteCodeService service = newInviteCodeService(true);

        assertThrows(BizException.class, () -> service.redeemForRegistration(null));
    }

    @Test
    void shouldConsumeInviteCodeUntilMaxUses() {
        InviteCodeService service = newInviteCodeService(true);
        var inviteCode = service.create(1L, new CreateInviteCodeRequest("jlu2026", 2, LocalDateTime.now().plusDays(1), "灰度"));

        assertEquals("JLU2026", inviteCode.code());
        assertDoesNotThrow(() -> service.redeemForRegistration("jlu2026"));
        assertDoesNotThrow(() -> service.redeemForRegistration("JLU2026"));
        assertThrows(BizException.class, () -> service.redeemForRegistration("JLU2026"));
    }

    @Test
    void shouldRejectExpiredInviteCode() {
        InviteCodeService service = newInviteCodeService(true);
        service.create(1L, new CreateInviteCodeRequest("expired", 1, LocalDateTime.now().minusMinutes(1), "过期"));

        assertThrows(BizException.class, () -> service.redeemForRegistration("expired"));
    }

    private InviteCodeService newInviteCodeService(boolean required) {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setInviteCodeRequired(required);
        return new InviteCodeService(
                properties,
                new InMemoryInviteCodeRepository(),
                new AdminAuditService(new InMemoryAdminOperationLogRepository())
        );
    }
}
