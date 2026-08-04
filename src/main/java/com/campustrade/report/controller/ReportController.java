package com.campustrade.report.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.report.dto.CreateReportRequest;
import com.campustrade.report.dto.ReportResponse;
import com.campustrade.report.service.ReportService;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    @RateLimit(key = "report:create", permits = 10, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<ReportResponse> create(@Valid @RequestBody CreateReportRequest request) {
        return ApiResponse.ok(reportService.create(CurrentUserContext.require().id(), request));
    }
}
