package com.campustrade.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReportRequest(
        @NotBlank @Size(max = 20) String targetType,
        @NotNull Long targetId,
        @NotBlank @Size(max = 80) String reason,
        @Size(max = 500) String description
) {
}
