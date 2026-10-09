package com.campustrade.notification.admin;

import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.NotificationOutboxRepository;
import com.campustrade.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class NotificationRecoveryService {
    private final NotificationOutboxRepository repository;
    private final AdminAuditService audit;

    public NotificationRecoveryService(NotificationOutboxRepository repository, AdminAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<NotificationOutboxEvent> list(AuthenticatedUser admin, OutboxStatus status, int limit) {
        requireAdmin(admin);
        return repository.findByStatus(status, limit);
    }

    @Transactional
    public NotificationOutboxEvent replayFailed(AuthenticatedUser admin, String eventId, String reason) {
        requireAdmin(admin);
        requireReason(reason);
        NotificationOutboxEvent event = requireEvent(eventId);
        if (!repository.replay(eventId, false)) { throw BizException.conflict("只有失败事件可以重放"); }
        audit.record(admin.id(), "REPLAY_NOTIFICATION", "OUTBOX", event.getId(), eventId + ": " + reason.trim());
        return requireEvent(eventId);
    }

    /** Called through the transactional proxy; the AMQP caller ACKs only after this transaction commits. */
    @Transactional
    public NotificationOutboxEvent recoverDeadLetter(AuthenticatedUser admin, NotificationEventPayload payload, String reason) {
        requireAdmin(admin);
        requireReason(reason);
        NotificationOutboxEvent event = requireEvent(payload.eventId());
        if (!Objects.equals(NotificationEventPayload.from(event), payload)) {
            throw BizException.conflict("死信内容与持久事件不一致，需人工检查");
        }
        boolean replayed = repository.replay(payload.eventId(), true);
        NotificationOutboxEvent latest = requireEvent(payload.eventId());
        if (!replayed && latest.getStatus() != OutboxStatus.PENDING && latest.getStatus() != OutboxStatus.PROCESSING) {
            throw BizException.conflict("当前事件状态不允许重放");
        }
        // A duplicate dead letter after commit-before-ACK is acknowledged, without resetting an active task.
        audit.record(admin.id(), "REPLAY_NOTIFICATION_DLQ", "OUTBOX", event.getId(),
                payload.eventId() + ": " + (replayed ? "scheduled; " : "already scheduled; ") + reason.trim());
        return latest;
    }

    public static void requireAdmin(AuthenticatedUser admin) {
        if (admin == null || admin.role() != UserRole.ADMIN) { throw BizException.forbidden("需要管理员权限"); }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3 || reason.length() > 300) {
            throw BizException.badRequest("重放原因长度必须为 3 至 300 字符");
        }
    }

    private NotificationOutboxEvent requireEvent(String eventId) {
        return repository.findByEventId(eventId).orElseThrow(() -> BizException.notFound("通知事件不存在"));
    }
}
