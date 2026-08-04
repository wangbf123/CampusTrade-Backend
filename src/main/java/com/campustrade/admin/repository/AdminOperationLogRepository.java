package com.campustrade.admin.repository;

import com.campustrade.admin.model.AdminOperationLog;

public interface AdminOperationLogRepository {

    AdminOperationLog save(AdminOperationLog log);
}
