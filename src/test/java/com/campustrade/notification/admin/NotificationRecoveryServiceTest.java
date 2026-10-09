package com.campustrade.notification.admin;

import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.repository.InMemoryNotificationOutboxRepository;
import com.campustrade.notification.service.NotificationOutboxService;
import com.campustrade.user.model.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRecoveryServiceTest {
    private final InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final NotificationRecoveryService recovery = new NotificationRecoveryService(repository, audit);
    private final AuthenticatedUser admin = new AuthenticatedUser(9L, "admin", UserRole.ADMIN);

    @Test
    void rejectsUserReplayAndRequiresReason() {
        assertThrows(BizException.class, () -> recovery.list(new AuthenticatedUser(1L, "user", UserRole.USER), null, 20));
        assertThrows(BizException.class, () -> recovery.replayFailed(admin, "event", " "));
        verifyNoInteractions(audit);
    }

    @Test
    void replayIsAuditedAndDoesNotReplaceEventId() {
        var event = new NotificationOutboxService(repository).enqueue(1L, "TEST", "title", "content", null);
        var claim = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
        repository.markFailed(event.getEventId(), claim.getClaimToken(), "error", 1, 1);
        var replayed = recovery.replayFailed(admin, event.getEventId(), "broker restored");
        assertEquals(event.getEventId(), replayed.getEventId());
        verify(audit).record(eq(9L), eq("REPLAY_NOTIFICATION"), eq("OUTBOX"), eq(event.getId()), contains("broker restored"));
    }

    @Test
    void rejectsUntrustedDeadLetterAndDuplicateRecoveryDoesNotResetActiveTask() {
        var event = new NotificationOutboxService(repository).enqueue(1L, "TEST", "title", "content", null);
        var claim = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
        repository.markPublished(event.getEventId(), claim.getClaimToken());
        var forged = new NotificationEventPayload(event.getEventId(), 2L, "TEST", "title", "content", null);
        assertThrows(BizException.class, () -> recovery.recoverDeadLetter(admin, forged, "inspect failure"));
        recovery.recoverDeadLetter(admin, NotificationEventPayload.from(event), "broker restored");
        var active = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
        var repeated = recovery.recoverDeadLetter(admin, NotificationEventPayload.from(event), "duplicate after lost ack");
        assertEquals(active.getClaimToken(), repeated.getClaimToken());
        assertEquals(1, repeated.getReplayCount());
    }
}
