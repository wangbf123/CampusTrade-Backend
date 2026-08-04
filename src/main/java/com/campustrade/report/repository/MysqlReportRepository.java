package com.campustrade.report.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.report.mapper.ReportMapper;
import com.campustrade.report.model.Report;
import com.campustrade.report.model.ReportStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MysqlReportRepository implements ReportRepository {

    private final ReportMapper reportMapper;

    public MysqlReportRepository(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    @Override
    public Report save(Report report) {
        LocalDateTime now = LocalDateTime.now();
        if (report.getId() == null) {
            report.setCreatedAt(now);
            report.setUpdatedAt(now);
            reportMapper.insert(report);
            return report;
        }
        report.setUpdatedAt(now);
        reportMapper.updateById(report);
        return report;
    }

    @Override
    public Optional<Report> findById(Long id) {
        return Optional.ofNullable(reportMapper.selectById(id));
    }

    @Override
    public List<Report> findByStatus(ReportStatus status, int page, int size) {
        int offset = (page - 1) * size;
        return reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(status != null, Report::getStatus, status)
                .orderByDesc(Report::getCreatedAt)
                .orderByDesc(Report::getId)
                .last("LIMIT " + offset + ", " + size));
    }
}
