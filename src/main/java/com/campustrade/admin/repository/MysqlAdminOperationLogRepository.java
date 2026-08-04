package com.campustrade.admin.repository;

import com.campustrade.admin.mapper.AdminOperationLogMapper;
import com.campustrade.admin.model.AdminOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@Profile("mysql")
public class MysqlAdminOperationLogRepository implements AdminOperationLogRepository {

    private final AdminOperationLogMapper adminOperationLogMapper;

    public MysqlAdminOperationLogRepository(AdminOperationLogMapper adminOperationLogMapper) {
        this.adminOperationLogMapper = adminOperationLogMapper;
    }

    @Override
    public AdminOperationLog save(AdminOperationLog log) {
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(LocalDateTime.now());
        }
        adminOperationLogMapper.insert(log);
        return log;
    }
}
