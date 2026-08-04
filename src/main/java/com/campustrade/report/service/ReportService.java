package com.campustrade.report.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.report.dto.CreateReportRequest;
import com.campustrade.report.dto.ReportResponse;
import com.campustrade.report.dto.ResolveReportRequest;
import com.campustrade.report.model.Report;
import com.campustrade.report.model.ReportStatus;
import com.campustrade.report.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class ReportService {

    private final ReportRepository reportRepository;

    public ReportService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Transactional
    public ReportResponse create(Long reporterId, CreateReportRequest request) {
        Report report = new Report();
        report.setReporterId(reporterId);
        report.setTargetType(request.targetType().trim().toUpperCase(Locale.ROOT));
        report.setTargetId(request.targetId());
        report.setReason(request.reason().trim());
        report.setDescription(request.description());
        report.setStatus(ReportStatus.PENDING);
        reportRepository.save(report);
        return ReportResponse.from(report);
    }

    public List<ReportResponse> list(ReportStatus status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return reportRepository.findByStatus(status, safePage, safeSize).stream()
                .map(ReportResponse::from)
                .toList();
    }

    @Transactional
    public ReportResponse resolve(Long adminId, Long reportId, ResolveReportRequest request) {
        if (request.status() == ReportStatus.PENDING) {
            throw BizException.badRequest("处理结果不能仍为 PENDING");
        }
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> BizException.notFound("举报不存在"));
        report.setStatus(request.status());
        report.setAuditUserId(adminId);
        report.setAuditResult(request.auditResult());
        reportRepository.save(report);
        return ReportResponse.from(report);
    }
}
