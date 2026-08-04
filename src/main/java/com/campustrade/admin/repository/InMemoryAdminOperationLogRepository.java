package com.campustrade.admin.repository;

import com.campustrade.admin.model.AdminOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryAdminOperationLogRepository implements AdminOperationLogRepository {

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, AdminOperationLog> logs = new ConcurrentHashMap<>();

    @Override
    public synchronized AdminOperationLog save(AdminOperationLog log) {
        if (log.getId() == null) {
            log.setId(idGenerator.getAndIncrement());
        }
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(LocalDateTime.now());
        }
        logs.put(log.getId(), log);
        return log;
    }
}
