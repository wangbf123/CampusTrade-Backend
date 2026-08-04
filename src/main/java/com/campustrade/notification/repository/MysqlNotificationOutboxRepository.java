package com.campustrade.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.notification.mapper.NotificationOutboxMapper;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
    public long countPendingDue(LocalDateTime now) {
        return outboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, OutboxStatus.PENDING)
                .and(wrapper -> wrapper
                        .isNull(NotificationOutboxEvent::getNextRetryAt)
                        .or()
                        .le(NotificationOutboxEvent::getNextRetryAt, now)));
    }

    @Override
    public boolean markPublished(String eventId) {
        int rows = outboxMapper.update(null, new LambdaUpdateWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getEventId, eventId)
                .eq(NotificationOutboxEvent::getStatus, OutboxStatus.PENDING)
                .set(NotificationOutboxEvent::getStatus, OutboxStatus.PUBLISHED)
                .set(NotificationOutboxEvent::getUpdatedAt, LocalDateTime.now()));
        return rows == 1;
    }

    @Override
    public boolean markFailed(String eventId, String reason, LocalDateTime nextRetryAt, int maxRetry) {
        int safeMaxRetry = Math.max(1, maxRetry);
        int rows = outboxMapper.update(null, new LambdaUpdateWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getEventId, eventId)
                .eq(NotificationOutboxEvent::getStatus, OutboxStatus.PENDING)
                .set(NotificationOutboxEvent::getLastError, reason)
                .set(NotificationOutboxEvent::getNextRetryAt, nextRetryAt)
                .set(NotificationOutboxEvent::getUpdatedAt, LocalDateTime.now())
                .setSql("retry_count = retry_count + 1")
                .setSql("status = CASE WHEN retry_count + 1 >= " + safeMaxRetry
                        + " THEN 'FAILED' ELSE 'PENDING' END"));
        return rows == 1;
    }
}
