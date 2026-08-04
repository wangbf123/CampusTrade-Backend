package com.campustrade.report.dto;

import com.campustrade.report.model.Report;
import com.campustrade.report.model.ReportStatus;

import java.time.LocalDateTime;

public record ReportResponse(
        Long id,
        Long reporterId,
        String targetType,
        Long targetId,
        String reason,
        String description,
        ReportStatus status,
        Long auditUserId,
        String auditResult,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getReporterId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDescription(),
                report.getStatus(),
                report.getAuditUserId(),
                report.getAuditResult(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}
