package com.campustrade.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.notification.mapper.NotificationOutboxMapper;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MysqlNotificationOutboxRepository implements NotificationOutboxRepository {

    private final NotificationOutboxMapper outboxMapper;

    public MysqlNotificationOutboxRepository(NotificationOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Override
    public NotificationOutboxEvent save(NotificationOutboxEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getId() == null) {
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(now);
            }
            event.setUpdatedAt(now);
            outboxMapper.insert(event);
            return event;
        }

        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
        return event;
    }

    @Override
    public Optional<NotificationOutboxEvent> findByEventId(String eventId) {
        NotificationOutboxEvent event = outboxMapper.selectOne(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getEventId, eventId)
                .last("LIMIT 1"));
        return Optional.ofNullable(event);
    }

    @Override
    public List<NotificationOutboxEvent> findPendingDue(LocalDateTime now, int limit) {
        int safeLimit = Math.max(1, limit);
        return outboxMapper.selectList(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, OutboxStatus.PENDING)
                .and(wrapper -> wrapper
                        .isNull(NotificationOutboxEvent::getNextRetryAt)
                        .or()
                        .le(NotificationOutboxEvent::getNextRetryAt, now))
                .orderByAsc(NotificationOutboxEvent::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return outboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, status));
    }

    @Override
    public long countPendingDue(LocalDateTime now) { return outboxMapper.countPendingDue(); }

    /** The row locks and claim updates commit before any remote publish occurs. Database time owns leases. */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<NotificationOutboxEvent> claimDue(LocalDateTime now, int limit, Duration lease) {
        int safeLimit = Math.min(100, Math.max(1, limit));
        List<NotificationOutboxEvent> events = new ArrayList<>(outboxMapper.selectExpiredClaimCandidates(safeLimit));
        if (events.size() < safeLimit) {
            events.addAll(outboxMapper.selectPendingClaimCandidates(safeLimit - events.size()));
        }
        for (NotificationOutboxEvent event : events) {
            String token = UUID.randomUUID().toString();
            outboxMapper.claim(event.getId(), token, Math.max(1, lease.toSeconds()));
            event.setStatus(OutboxStatus.PROCESSING);
            event.setClaimToken(token);
        }
        return events;
    }

    @Override
    public boolean renewLease(String eventId, String claimToken, Duration lease) {
        return outboxMapper.renewLease(eventId, claimToken, Math.max(1, lease.toSeconds())) == 1;
    }

    @Override
    public boolean markPublished(String eventId, String claimToken) {
        return outboxMapper.markPublished(eventId, claimToken) == 1;
    }

    @Override
    public boolean markFailed(String eventId, String claimToken, String reason, long retryDelaySeconds, int maxRetry) {
        String boundedReason = reason == null ? "Publish failed" : reason.substring(0, Math.min(500, reason.length()));
        return outboxMapper.markFailed(eventId, claimToken, boundedReason,
                Math.max(1, retryDelaySeconds), Math.max(1, maxRetry)) == 1;
    }

    @Override
    public boolean replay(String eventId, boolean includePublished) {
        LambdaUpdateWrapper<NotificationOutboxEvent> update = new LambdaUpdateWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getEventId, eventId);
        if (includePublished) {
            update.in(NotificationOutboxEvent::getStatus, OutboxStatus.FAILED, OutboxStatus.PUBLISHED);
        } else {
            update.eq(NotificationOutboxEvent::getStatus, OutboxStatus.FAILED);
        }
        return outboxMapper.update(null, update
                .set(NotificationOutboxEvent::getStatus, OutboxStatus.PENDING)
                .set(NotificationOutboxEvent::getRetryCount, 0)
                .set(NotificationOutboxEvent::getLastError, null)
                .set(NotificationOutboxEvent::getClaimToken, null)
                .set(NotificationOutboxEvent::getLeaseUntil, null)
                .setSql("next_retry_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6), replay_count = replay_count + 1")) == 1;
    }

    @Override
    public List<NotificationOutboxEvent> findByStatus(OutboxStatus status, int limit) {
        return outboxMapper.selectList(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, status)
                .orderByAsc(NotificationOutboxEvent::getCreatedAt, NotificationOutboxEvent::getId)
                .last("LIMIT " + Math.min(100, Math.max(1, limit))));
    }

    @Override
    public long countExpiredLeases(LocalDateTime now) { return outboxMapper.countExpiredLeases(); }

    @Override
    public long oldestUnpublishedAgeSeconds(LocalDateTime now) { return outboxMapper.oldestUnpublishedAgeSeconds(); }
}
