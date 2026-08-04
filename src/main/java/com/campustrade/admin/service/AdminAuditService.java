package com.campustrade.admin.service;

import com.campustrade.admin.model.AdminOperationLog;
import com.campustrade.admin.repository.AdminOperationLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AdminAuditService {

    private final AdminOperationLogRepository adminOperationLogRepository;

    public AdminAuditService(AdminOperationLogRepository adminOperationLogRepository) {
        this.adminOperationLogRepository = adminOperationLogRepository;
    }

    public void record(Long adminId, String operationType, String targetType, Long targetId, String detail) {
        AdminOperationLog log = new AdminOperationLog();
        log.setAdminId(adminId);
        log.setOperationType(operationType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        adminOperationLogRepository.save(log);
    }
}
