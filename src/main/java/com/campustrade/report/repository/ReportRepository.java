package com.campustrade.report.repository;

import com.campustrade.report.model.Report;
import com.campustrade.report.model.ReportStatus;

import java.util.List;
import java.util.Optional;

public interface ReportRepository {

    Report save(Report report);

    Optional<Report> findById(Long id);

    List<Report> findByStatus(ReportStatus status, int page, int size);
}
