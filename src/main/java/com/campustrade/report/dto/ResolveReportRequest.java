package com.campustrade.report.dto;

import com.campustrade.report.model.ReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveReportRequest(
        @NotNull ReportStatus status,
        @Size(max = 500) String auditResult
) {
}
